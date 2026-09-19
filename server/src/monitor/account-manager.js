import fs from 'node:fs/promises';
import { watch, existsSync } from 'node:fs';
import path from 'node:path';
import { queryAll, queryOne } from '../db/index.js';
import {
  ensureAccountsSchema,
  updateAccountRuntimeState,
  updateAccountCredentials,
  getSignalFilePath,
  extractSessionCredentials,
  readBrowserSession,
} from '../services/account-service.js';
import { recordError } from '../services/error-log.js';
import { NogiBrowserMonitor } from './nogi-browser.js';

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

class AccountManager {
  constructor() {
    this.workers = new Map(); // accountId -> { worker, account }
    this.isRunning = false;
    this.signalWatcher = null;
    this.signalTimer = null;
    this.periodicCheckTimer = null;
    this.legacyFileWatcher = null;
    this.lastSignalTimestamp = 0;
    this.signalFilePath = getSignalFilePath();
    this.legacyStateFilePath = process.env.NOGI_BROWSER_STATE_FILE || '/data/nogi-browser-state.json';
  }

  /**
   * 启动多账号管理器
   */
  async start() {
    if (this.isRunning) return;
    this.isRunning = true;
    console.log('AccountManager 正在启动...');

    try {
      await ensureAccountsSchema();
      await this.loadAndStartAccounts();
      await this.startSignalWatcher();
      await this.startLegacyWatcher();

      // 每 60 秒例行检查一次数据库账号变动，作为信号监听的保底机制
      this.periodicCheckTimer = setInterval(() => {
        if (this.isRunning) {
          this.syncAccountsFromDb().catch(err => {
            console.warn('[AccountManager] 定期轮询同步账号失败:', err.message);
          });
        }
      }, 60_000);
      this.periodicCheckTimer.unref?.();

      console.log(`AccountManager 启动完成，当前托管账号数: ${this.workers.size}`);
    } catch (error) {
      console.error('AccountManager 启动失败:', error.message);
      await recordError('account_manager.start', error);
      throw error;
    }
  }

  /**
   * 从数据库加载所有账号并启动工作进程
   */
  async loadAndStartAccounts() {
    const accounts = await queryAll(
      `SELECT id, name, status, session_cookie, access_token, token_expires_at
       FROM accounts
       ORDER BY created_at ASC`,
    );

    if (accounts.length === 0) {
      console.warn('[AccountManager] 当前数据库无配置账号，等待通过管理界面或接口添加');
      return;
    }

    console.log(`[AccountManager] 发现 ${accounts.length} 个账号，正在按序启动...`);
    for (let i = 0; i < accounts.length; i++) {
      const account = accounts[i];
      if (account.status === 'disabled') {
        console.log(`[AccountManager] 账号 ${account.name} (${account.id}) 已停用，跳过启动`);
        continue;
      }

      await this.spawnWorker(account);

      // 错峰启动，避免并发冲击官方接口
      if (i < accounts.length - 1) {
        await sleep(2000);
      }
    }
  }

  /**
   * 创建并启动单个账号 worker
   */
  async spawnWorker(account) {
    if (this.workers.has(account.id)) {
      await this.stopWorker(account.id);
    }

    const worker = new NogiBrowserMonitor({
      accountId: account.id,
      accountName: account.name,
      sessionCookie: account.session_cookie,
      accessToken: account.access_token,
      onStateChange: async (updates) => {
        try {
          await updateAccountRuntimeState(account.id, updates);
        } catch (err) {
          console.warn(`[${account.name}] 更新运行状态至数据库异常:`, err.message);
        }
      },
    });

    this.workers.set(account.id, { worker, account });

    worker.start().catch(error => {
      console.error(`[AccountManager] 账号 ${account.name} (${account.id}) worker 异常退出:`, error.message);
      void recordError('account_manager.worker_error', error, { accountId: account.id });
    });

    return worker;
  }

  /**
   * 停止单个账号 worker
   */
  async stopWorker(accountId) {
    const entry = this.workers.get(accountId);
    if (!entry) return;

    this.workers.delete(accountId);
    try {
      await entry.worker.stop();
      console.log(`[AccountManager] 账号 ${entry.account.name} (${accountId}) worker 已停止`);
    } catch (err) {
      console.warn(`[AccountManager] 停止账号 ${accountId} worker 异常:`, err.message);
    }
  }

  /**
   * 比对数据库与内存中的 workers，增删改查对齐
   */
  async syncAccountsFromDb() {
    const accounts = await queryAll(
      `SELECT id, name, status, session_cookie, access_token, token_expires_at
       FROM accounts`,
    );
    const dbAccountMap = new Map(accounts.map(a => [a.id, a]));

    // 1. 处理已删除或被停用的账号
    for (const [accountId, entry] of this.workers.entries()) {
      const dbAcc = dbAccountMap.get(accountId);
      if (!dbAcc) {
        console.log(`[AccountManager] 账号 ${accountId} 已在数据库中删除，停止 worker`);
        await this.stopWorker(accountId);
      } else if (dbAcc.status === 'disabled') {
        console.log(`[AccountManager] 账号 ${dbAcc.name} (${accountId}) 已停用，停止 worker`);
        await this.stopWorker(accountId);
      }
    }

    // 2. 处理新增或凭据更新的账号
    for (const account of accounts) {
      if (account.status === 'disabled') continue;

      const existing = this.workers.get(account.id);
      if (!existing) {
        console.log(`[AccountManager] 发现新启用账号 ${account.name} (${account.id})，启动 worker`);
        await this.spawnWorker(account);
      } else {
        // 检查凭据或别名是否变更
        const cookieChanged = existing.worker.sessionCookie !== account.session_cookie;
        const nameChanged = existing.account.name !== account.name;

        if (nameChanged) {
          existing.worker.accountName = account.name;
          existing.account.name = account.name;
        }

        if (cookieChanged) {
          console.log(`[AccountManager] 账号 ${account.name} (${account.id}) 凭据已更新，重新应用凭据`);
          existing.account = account;
          await existing.worker.updateCredentials({
            sessionCookie: account.session_cookie,
            accessToken: account.access_token,
          });
        }
      }
    }
  }

  /**
   * 监听跨进程通信信号文件
   */
  async startSignalWatcher() {
    const signalDir = path.dirname(this.signalFilePath);
    await fs.mkdir(signalDir, { recursive: true });

    try {
      this.signalWatcher = watch(signalDir, { persistent: false }, (eventType, filename) => {
        if (filename && filename !== path.basename(this.signalFilePath)) return;

        if (this.signalTimer) clearTimeout(this.signalTimer);
        this.signalTimer = setTimeout(async () => {
          this.signalTimer = null;
          try {
            const content = await fs.readFile(this.signalFilePath, 'utf8');
            const data = JSON.parse(content);
            if (data?.timestamp && data.timestamp > this.lastSignalTimestamp) {
              this.lastSignalTimestamp = data.timestamp;
              console.log(`[AccountManager] 收到账号变动信号: action=${data.action}, accountId=${data.accountId || 'all'}`);

              if (data.action === 'sync' && data.accountId) {
                const entry = this.workers.get(data.accountId);
                if (entry) {
                  await entry.worker.triggerPoll();
                  return;
                }
              }

              await this.syncAccountsFromDb();
            }
          } catch (err) {
            if (err.code !== 'ENOENT') {
              console.warn('[AccountManager] 读取信号文件异常:', err.message);
            }
          }
        }, 150);
      });
      console.log(`[AccountManager] 开始监听账号变动信号文件: ${this.signalFilePath}`);
    } catch (error) {
      console.warn('[AccountManager] 启动信号监听失败:', error.message);
    }
  }

  /**
   * 监听旧版单账号会话文件变更以保持 100% 脚本向后兼容
   */
  async startLegacyWatcher() {
    const legacyDir = path.dirname(this.legacyStateFilePath);
    const legacyBase = path.basename(this.legacyStateFilePath);
    await fs.mkdir(legacyDir, { recursive: true });

    try {
      this.legacyFileWatcher = watch(legacyDir, { persistent: false }, (eventType, filename) => {
        if (filename && filename !== legacyBase) return;

        setTimeout(async () => {
          try {
            const { credentials } = await readBrowserSession(this.legacyStateFilePath);
            if (credentials?.sessionCookie) {
              const defaultAcc = await queryOne("SELECT session_cookie FROM accounts WHERE id = 'acc_default'");
              if (!defaultAcc || defaultAcc.session_cookie !== credentials.sessionCookie) {
                console.log('[AccountManager] 检测到旧版会话文件凭据更新，自动同步至 acc_default');
                await updateAccountCredentials('acc_default', {
                  sessionCookie: credentials.sessionCookie,
                  accessToken: credentials.accessToken || null,
                  tokenExpiresAt: credentials.expiresAt || null,
                  status: 'active',
                });
              }
            }
          } catch (err) {
            if (err.code !== 'ENOENT') {
              console.warn('[AccountManager] 读取旧版会话异常:', err.message);
            }
          }
        }, 300);
      });
      console.log(`[AccountManager] 开始监听旧版会话文件兼容路径: ${this.legacyStateFilePath}`);
    } catch (error) {
      console.warn('[AccountManager] 监听旧版会话文件失败:', error.message);
    }
  }

  /**
   * 优雅停止全部 workers 及监听
   */
  async stop() {
    this.isRunning = false;
    if (this.periodicCheckTimer) clearInterval(this.periodicCheckTimer);
    if (this.signalTimer) clearTimeout(this.signalTimer);
    if (this.signalWatcher) {
      this.signalWatcher.close();
      this.signalWatcher = null;
    }
    if (this.legacyFileWatcher) {
      this.legacyFileWatcher.close();
      this.legacyFileWatcher = null;
    }

    console.log('[AccountManager] 正在停止所有账号 worker...');
    const stopPromises = Array.from(this.workers.keys()).map(id => this.stopWorker(id));
    await Promise.allSettled(stopPromises);
    this.workers.clear();
    console.log('[AccountManager] 所有账号 worker 已停止');
  }
}

const accountManager = new AccountManager();
export { AccountManager, accountManager };
export default accountManager;
