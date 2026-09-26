import fs from 'node:fs/promises';
import path from 'node:path';
import { query as dbQuery, queryOne, queryAll } from '../db/index.js';
export { readBrowserSession, extractSessionCredentials } from './browser-session.js';
import { recordError } from './error-log.js';

const SIGNAL_FILE = process.env.NOGI_ACCOUNTS_SIGNAL_FILE
  || (process.env.NOGI_BROWSER_STATE_FILE
    ? path.join(path.dirname(process.env.NOGI_BROWSER_STATE_FILE), 'nogi-accounts-signal.json')
    : './nogi-accounts-signal.json');

/**
 * 确保 accounts 表结构与消息关联字段存在，并在首次启动时平滑迁移旧单账号会话
 */
export async function ensureAccountsSchema() {
  try {
    await dbQuery(`
      CREATE TABLE IF NOT EXISTS accounts (
          id VARCHAR(64) PRIMARY KEY,
          name VARCHAR(255) NOT NULL,
          status VARCHAR(32) NOT NULL DEFAULT 'active',
          session_cookie TEXT NOT NULL,
          access_token TEXT,
          token_expires_at TIMESTAMPTZ,
          token_refreshed_at TIMESTAMPTZ,
          token_refresh_state VARCHAR(32),
          token_refresh_error TEXT,
          consecutive_failures INTEGER NOT NULL DEFAULT 0,
          last_sync_at TIMESTAMPTZ,
          last_error TEXT,
          subscribed_groups JSONB DEFAULT '[]',
          metadata JSONB DEFAULT '{}',
          created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
      );
      CREATE INDEX IF NOT EXISTS idx_accounts_status ON accounts(status);
      ALTER TABLE accounts ADD COLUMN IF NOT EXISTS token_refreshed_at TIMESTAMPTZ;
      ALTER TABLE accounts ADD COLUMN IF NOT EXISTS token_refresh_state VARCHAR(32);
      ALTER TABLE accounts ADD COLUMN IF NOT EXISTS token_refresh_error TEXT;
      ALTER TABLE messages ADD COLUMN IF NOT EXISTS source_accounts JSONB DEFAULT '[]';
    `);

    // 检查是否有现有账号
    const countRes = await queryOne('SELECT COUNT(*)::int AS count FROM accounts');
    if (countRes && countRes.count === 0) {
      // 尝试从旧版会话文件迁移第一个主账号
      const legacyPath = process.env.NOGI_BROWSER_STATE_FILE || '/data/nogi-browser-state.json';
      try {
        const fileContent = await fs.readFile(legacyPath, 'utf8');
        const parsed = JSON.parse(fileContent);
        const credentials = extractSessionCredentials(parsed);
        if (credentials?.sessionCookie) {
          await dbQuery(
            `INSERT INTO accounts (id, name, status, session_cookie, access_token, token_expires_at)
             VALUES ($1, $2, $3, $4, $5, $6)
             ON CONFLICT (id) DO NOTHING`,
            [
              'acc_default',
              '主订阅账号',
              'active',
              credentials.sessionCookie,
              credentials.accessToken || null,
              credentials.expiresAt || null,
            ],
          );
          console.log(`[account-service] 已将旧版会话 (${legacyPath}) 自动迁移为主账号 acc_default`);
        }
      } catch (err) {
        if (err.code !== 'ENOENT') {
          console.warn('[account-service] 检查旧版会话文件异常:', err.message);
        }
      }
    }
    console.log('[account-service] 数据库 accounts 表结构验证完成');
  } catch (error) {
    await recordError('account_service.ensure_schema', error);
  }
}

/**
 * 触发跨进程账号变动通知信号
 */
export async function touchAccountSignal(action = 'update', accountId = null) {
  try {
    const dir = path.dirname(SIGNAL_FILE);
    await fs.mkdir(dir, { recursive: true });
    const payload = JSON.stringify({
      timestamp: Date.now(),
      action,
      accountId,
    });
    const tmp = `${SIGNAL_FILE}.tmp-${Date.now()}`;
    await fs.writeFile(tmp, payload, 'utf8');
    await fs.rename(tmp, SIGNAL_FILE);
  } catch (error) {
    console.warn('[account-service] 写入跨进程变动信号失败:', error.message);
  }
}

export function getSignalFilePath() {
  return SIGNAL_FILE;
}

/**
 * 脱敏凭据
 */
function maskSecret(str) {
  if (!str || typeof str !== 'string') return '';
  if (str.length <= 8) return '***';
  return `${str.slice(0, 4)}***${str.slice(-4)}`;
}

/**
 * 查询所有账号列表 (脱敏展示)
 */
export async function listAccounts() {
  const rows = await queryAll(
    `SELECT id, name, status, session_cookie, access_token, token_expires_at,
            token_refreshed_at, token_refresh_state, token_refresh_error,
            consecutive_failures, last_sync_at, last_error, subscribed_groups,
            metadata, created_at, updated_at
     FROM accounts
     ORDER BY created_at ASC`,
  );

  return rows.map(r => ({
    ...r,
    session_cookie: maskSecret(r.session_cookie),
    access_token: maskSecret(r.access_token),
    has_valid_token: Boolean(r.access_token && (!r.token_expires_at || new Date(r.token_expires_at).getTime() > Date.now())),
  }));
}

/**
 * 获取单个账号 (可选择是否返回敏感凭据)
 */
export async function getAccount(id, { includeCredentials = false } = {}) {
  const row = await queryOne('SELECT * FROM accounts WHERE id = $1', [id]);
  if (!row) return null;
  if (!includeCredentials) {
    row.session_cookie = maskSecret(row.session_cookie);
    row.access_token = maskSecret(row.access_token);
  }
  return row;
}

/**
 * 新增账号
 */
export async function createAccount({ id, name, sessionCookie, accessToken = null, tokenExpiresAt = null, metadata = {} }) {
  const cleanId = String(id || '').trim();
  const cleanName = String(name || '').trim();
  const cleanCookie = String(sessionCookie || '').trim();

  if (!cleanId || !cleanName || !cleanCookie) {
    throw new Error('id, name, sessionCookie 不能为空');
  }

  const result = await queryOne(
    `INSERT INTO accounts (id, name, status, session_cookie, access_token, token_expires_at, token_refresh_state, token_refreshed_at, metadata)
     VALUES ($1, $2, 'active', $3, $4, $5, 'active', CURRENT_TIMESTAMP, $6)
     RETURNING id, name, status, created_at`,
    [cleanId, cleanName, cleanCookie, accessToken, tokenExpiresAt, JSON.stringify(metadata)],
  );

  await touchAccountSignal('create', cleanId);
  return result;
}

/**
 * 更新账号基础信息 (账号标识、别名/备注、启用/禁用状态)
 */
export async function updateAccountInfo(id, { name, status, newId }) {
  const cleanNewId = newId ? String(newId).trim() : null;
  const updates = [];
  const params = [];

  if (cleanNewId && cleanNewId !== id) {
    params.push(cleanNewId);
    updates.push(`id = $${params.length}`);
  }
  if (name != null) {
    params.push(String(name).trim());
    updates.push(`name = $${params.length}`);
  }
  if (status != null) {
    params.push(String(status).trim());
    updates.push(`status = $${params.length}`);
  }

  if (updates.length === 0) return null;

  updates.push('updated_at = CURRENT_TIMESTAMP');
  params.push(id);
  const whereIdx = params.length;

  const queryText = `UPDATE accounts SET ${updates.join(', ')} WHERE id = $${whereIdx} RETURNING id, name, status, updated_at`;
  const result = await queryOne(queryText, params);

  if (result && cleanNewId && cleanNewId !== id) {
    // 同步更新 messages 中的 source_accounts 映射
    try {
      await dbQuery(
        `UPDATE messages
         SET source_accounts = (
           SELECT jsonb_agg(CASE WHEN x = $1 THEN $2 ELSE x END)
           FROM jsonb_array_elements_text(source_accounts) AS x
         )
         WHERE source_accounts ? $1`,
        [id, cleanNewId],
      );
    } catch (e) {
      console.warn('更新消息 source_accounts 失败:', e.message);
    }
  }

  await touchAccountSignal('update', cleanNewId || id);
  return result;
}

/**
 * 更新账号的凭据信息 (重新激活)
 */
export async function updateAccountCredentials(id, { sessionCookie, accessToken, tokenExpiresAt, status = 'active' }) {
  const result = await queryOne(
    `UPDATE accounts
     SET session_cookie = $2,
         access_token = COALESCE($3, access_token),
         token_expires_at = COALESCE($4, token_expires_at),
         token_refresh_state = 'active',
         token_refreshed_at = CURRENT_TIMESTAMP,
         token_refresh_error = NULL,
         status = $5,
         consecutive_failures = 0,
         last_error = NULL,
         updated_at = CURRENT_TIMESTAMP
     WHERE id = $1
     RETURNING id, name, status, updated_at`,
    [id, sessionCookie, accessToken, tokenExpiresAt, status],
  );

  await touchAccountSignal('credentials', id);
  return result;
}

/**
 * 更新运行时轮询状态 (供 Monitor 写入)
 */
export async function updateAccountRuntimeState(id, {
  status,
  sessionCookie,
  accessToken,
  tokenExpiresAt,
  tokenRefreshedAt,
  tokenRefreshState,
  tokenRefreshError,
  consecutiveFailures,
  lastSyncAt,
  lastError,
  subscribedGroups,
}) {
  const updates = ['updated_at = CURRENT_TIMESTAMP'];
  const params = [id];

  if (status !== undefined) {
    params.push(status);
    updates.push(`status = $${params.length}`);
  }
  if (sessionCookie !== undefined) {
    params.push(sessionCookie);
    updates.push(`session_cookie = $${params.length}`);
  }
  if (accessToken !== undefined) {
    params.push(accessToken);
    updates.push(`access_token = $${params.length}`);
  }
  if (tokenExpiresAt !== undefined) {
    params.push(tokenExpiresAt);
    updates.push(`token_expires_at = $${params.length}`);
  }
  if (tokenRefreshState !== undefined) {
    params.push(tokenRefreshState);
    updates.push(`token_refresh_state = $${params.length}`);
  }
  if (tokenRefreshedAt !== undefined) {
    params.push(tokenRefreshedAt);
    updates.push(`token_refreshed_at = $${params.length}`);
  }
  if (tokenRefreshError !== undefined) {
    params.push(tokenRefreshError);
    updates.push(`token_refresh_error = $${params.length}`);
  }
  if (consecutiveFailures !== undefined) {
    params.push(consecutiveFailures);
    updates.push(`consecutive_failures = $${params.length}`);
  }
  if (lastSyncAt !== undefined) {
    params.push(lastSyncAt);
    updates.push(`last_sync_at = $${params.length}`);
  }
  if (lastError !== undefined) {
    params.push(lastError);
    updates.push(`last_error = $${params.length}`);
  }
  if (subscribedGroups !== undefined) {
    params.push(JSON.stringify(subscribedGroups));
    updates.push(`subscribed_groups = $${params.length}`);
  }

  await dbQuery(
    `UPDATE accounts SET ${updates.join(', ')} WHERE id = $1`,
    params,
  );
}

/**
 * 删除账号
 */
export async function deleteAccount(id) {
  const res = await dbQuery('DELETE FROM accounts WHERE id = $1', [id]);
  await touchAccountSignal('delete', id);
  return (res.rowCount || 0) > 0;
}

export function tokenExpiry(token) {
  try {
    const payload = token.split('.')[1];
    if (!payload) return null;
    const decoded = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
    return Number.isFinite(decoded.exp) ? new Date(decoded.exp * 1000) : null;
  } catch {
    return null;
  }
}

/**
 * 校验并使用官方 /v2/update_token 接口测试会话有效性
 */
export async function validateOfficialSession(sessionCookie) {
  const apiUrl = (process.env.NOGI_API_URL || 'https://api.message.nogizaka46.com').replace(/\/$/, '');
  const webUrl = (process.env.NOGI_WEB_URL || 'https://message.nogizaka46.com').replace(/\/$/, '');
  const appId = process.env.NOGI_APP_ID || 'jp.co.sonymusic.communication.nogizaka 2.5';
  const platform = process.env.NOGI_APP_PLATFORM || 'web';

  const res = await fetch(`${apiUrl}/v2/update_token`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Talk-App-Id': appId,
      'X-Talk-App-Platform': platform,
      'Origin': webUrl,
      'Referer': `${webUrl}/`,
      'Cookie': `session=${sessionCookie}`,
    },
    body: '{}',
  });

  if (res.status === 400) {
    return {
      valid: false,
      status: 400,
      error: '官网会话已失效 (400 Bad Request)，账号可能已在其他设备登录 Web 端',
    };
  }

  if (!res.ok) {
    const text = await res.text().catch(() => '');
    return {
      valid: false,
      status: res.status,
      error: `/v2/update_token 返回 HTTP ${res.status}: ${text.slice(0, 100)}`,
    };
  }

  const data = await res.json();
  const accessToken = String(data.access_token || '').trim();
  if (!accessToken) {
    return { valid: false, error: '接口未返回 access_token' };
  }

  let finalCookie = sessionCookie;
  const setCookie = res.headers.get('set-cookie');
  if (setCookie) {
    const match = setCookie.match(/session=([a-zA-Z0-9_-]+)/);
    if (match && match[1]) {
      finalCookie = match[1];
    }
  }

  const expiresAt = tokenExpiry(accessToken);
  return {
    valid: true,
    status: 200,
    accessToken,
    sessionCookie: finalCookie,
    tokenExpiresAt: expiresAt,
  };
}
