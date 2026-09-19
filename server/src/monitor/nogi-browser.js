import dotenv from 'dotenv';
import fs from 'node:fs/promises';
import { watch, existsSync } from 'node:fs';
import path from 'node:path';
import messageService from '../services/message.js';
import pushService from '../services/push.js';
import { recordError } from '../services/error-log.js';
import {
  atomicWritePrivateFile,
  atomicWritePrivateJson,
  browserSessionPaths,
  extractSessionCredentials,
  readBrowserSession,
  readJsonIfExists,
  sessionVersion,
} from '../services/browser-session.js';

dotenv.config();

const DEFAULT_API_URL = 'https://api.message.nogizaka46.com';
const DEFAULT_WEB_URL = 'https://message.nogizaka46.com';
const DEFAULT_APP_ID = 'jp.co.sonymusic.communication.nogizaka 2.5';
const DEFAULT_PLATFORM = 'web';
const DEFAULT_ORGANIZATION_ID = '1';
const DEFAULT_POLL_INTERVAL_MS = 60_000;
const DEFAULT_BROWSER_STATE_FILE = (process.platform === 'win32' || !existsSync('/data'))
  ? (existsSync('./nogi-browser-state.json') ? './nogi-browser-state.json' : (existsSync('./server/nogi-browser-state.json') ? './server/nogi-browser-state.json' : './nogi-browser-state.json'))
  : '/data/nogi-browser-state.json';
export const ACCESS_TOKEN_REFRESH_SKEW_MS = 3 * 60_000; // 提前 3 分钟刷新

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

function parseBoolean(value, fallback) {
  if (value == null || value === '') return fallback;
  return ['1', 'true', 'yes', 'on'].includes(String(value).toLowerCase());
}

function parseGroupIds(value) {
  return String(value || '')
    .split(',')
    .map(item => Number.parseInt(item.trim(), 10))
    .filter(Number.isInteger)
    .filter((id, index, ids) => ids.indexOf(id) === index);
}

function normalizeType(type) {
  const typeMap = {
    text: 'text',
    article: 'text',
    picture: 'image',
    photo: 'image',
    image: 'image',
    audio: 'audio',
    voice: 'audio',
    call: 'audio',
    video: 'video',
    movie: 'video',
  };
  return typeMap[String(type || '').toLowerCase()] || 'text';
}

function firstNonEmpty(...values) {
  return values.find(value => value != null && String(value).trim() !== '') ?? null;
}

function tokenExpiry(token) {
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
 * 纯 API 监控服务：维护会话 Cookie，通过官方 API 自动轮询消息与续期 Token，
 * 无需常驻无头 Chromium 实例。
 */
class NogiBrowserMonitor {
  constructor({
    messageStore = messageService,
    pusher = pushService,
    accountId = null,
    accountName = null,
    sessionCookie = '',
    accessToken = '',
    onStateChange = null,
  } = {}) {
    this.messageStore = messageStore;
    this.pusher = pusher;
    this.accountId = accountId;
    this.accountName = accountName || (accountId ? `Account ${accountId}` : '主订阅账号');
    this.onStateChange = onStateChange;
    this.apiUrl = (process.env.NOGI_API_URL || DEFAULT_API_URL).replace(/\/$/, '');
    this.webUrl = (process.env.NOGI_WEB_URL || DEFAULT_WEB_URL).replace(/\/$/, '');
    this.appId = process.env.NOGI_APP_ID || DEFAULT_APP_ID;
    this.platform = process.env.NOGI_APP_PLATFORM || DEFAULT_PLATFORM;
    this.organizationId = process.env.NOGI_ORGANIZATION_ID || DEFAULT_ORGANIZATION_ID;
    this.groupIds = parseGroupIds(process.env.NOGI_GROUP_IDS);
    this.pollIntervalMs = Math.max(
      Number.parseInt(process.env.NOGI_POLL_INTERVAL_SECONDS || '60', 10) * 1000,
      15_000,
    ) || DEFAULT_POLL_INTERVAL_MS;
    this.backfillOnStart = parseBoolean(process.env.NOGI_BACKFILL_ON_START, true);
    this.storageStateFile = process.env.NOGI_BROWSER_STATE_FILE || DEFAULT_BROWSER_STATE_FILE;
    this.accessTokenStateFile = process.env.NOGI_ACCESS_TOKEN_STATE_FILE
      || path.join(path.dirname(this.storageStateFile), 'nogi-access-token.json');

    this.requestTimeoutMs = Math.max(
      Number.parseInt(process.env.NOGI_BROWSER_REQUEST_TIMEOUT_SECONDS || '30', 10) * 1000,
      10_000,
    );

    // 凭据与令牌状态
    this.sessionCookie = sessionCookie || '';
    this.accessToken = accessToken || '';
    this.observedTokenAt = accessToken ? Date.now() : 0;
    this.lastPersistedAccessToken = '';

    // 业务轮询与群组状态
    this.hasCompletedInitialPoll = false;
    this.backfilledGroupIds = new Set();
    this.historyBackfillReason = 'startup';
    this.groupMessageIds = new Map();
    this.groups = new Map();

    // 循环控制与并发管理
    this.isRunning = false;
    this.loopPromise = null;
    this.activePollingPromise = null;
    this.refreshPromise = null;

    // 文件监听与重载
    this.sessionFileWatcher = null;
    this.sessionReloadTimer = null;
    this.pendingSessionReload = false;
    this.isReloadingSession = false;
    this.lastPersistedStorageVersion = '';
    this.lastHandledUploadRequestId = '';

    // 鉴权异常状态机
    this.consecutiveAuthFailures = 0;
    this.maxConsecutiveAuthFailures = Math.max(
      Number.parseInt(
        process.env.NOGI_MAX_TOKEN_REFRESH_FAILURES
          || process.env.NOGI_MAX_AUTH_FAILURES
          || '3',
        10,
      ),
      1,
    );
    this.authPaused = false;
    this.authState = 'starting';
    this.signedOutLogTimer = null;
    this.authResumeWaiters = new Set();
    this.sessionReloadWaiters = new Set();
  }

  assertAuthAllowed({ sessionActivation = false } = {}) {
    if (this.authPaused && !sessionActivation) {
      throw new Error(`Monitor paused (${this.authState}): session update required`);
    }
  }

  assertBrowserActivityAllowed(options) {
    return this.assertAuthAllowed(options);
  }

  normalizeMessage(rawMessage, group) {
    const type = normalizeType(rawMessage.type || rawMessage.content_type);
    const memberName = firstNonEmpty(rawMessage.member_name, rawMessage.memberName, group.name, '乃木坂46');
    const sentAt = firstNonEmpty(
      rawMessage.published_at,
      rawMessage.sent_at,
      rawMessage.created_at,
    );
    const id = firstNonEmpty(rawMessage.id, rawMessage.message_id);
    if (!id || !sentAt) return null;

    const isCanceled = rawMessage.state === 'canceled';

    return {
      id: String(id),
      member_id: String(firstNonEmpty(rawMessage.member_id, rawMessage.memberId, group.id)),
      member_name: memberName,
      member_avatar_url: firstNonEmpty(rawMessage.member_avatar_url, rawMessage.avatar, group.thumbnail),
      phone_image_url: firstNonEmpty(rawMessage.phone_image_url, rawMessage.phone_image, group.phone_image),
      type,
      text: firstNonEmpty(rawMessage.text, rawMessage.message),
      media_url: firstNonEmpty(rawMessage.file, rawMessage.media_url),
      thumbnail_url: firstNonEmpty(rawMessage.thumbnail, rawMessage.thumbnail_url),
      duration_seconds: Number.parseInt(firstNonEmpty(rawMessage.duration, rawMessage.duration_seconds, 0), 10) || null,
      sent_at: new Date(sentAt).toISOString(),
      incoming_call_from: type === 'audio' ? memberName : null,
      ringtone_url: null,
      original_data: rawMessage,
      is_canceled: isCanceled,
    };
  }

  async processMessage(message, sendPush) {
    try {
      const messageToSave = this.accountId ? { ...message, source_account_id: this.accountId } : message;
      const saveResult = await this.messageStore.saveMessage(messageToSave);
      const isNew = typeof saveResult === 'object' && saveResult !== null && 'isNew' in saveResult
        ? Boolean(saveResult.isNew)
        : Boolean(saveResult);

      const pushTarget = typeof saveResult === 'object' && saveResult !== null && saveResult.message
        ? saveResult.message
        : message;
      let pushed = false;

      // 不推送已撤回的消息
      if (sendPush && isNew && !message.is_canceled) {
        try {
          await this.pusher.pushMessage(pushTarget);
          pushed = true;
        } catch (error) {
          await recordError('monitor.push_message', error, { messageId: message.id });
        }
      }

      return { isNew, pushed, processed: true };
    } catch (error) {
      await recordError('monitor.store_message', error, {
        message_id: message.id,
        member_id: message.member_id,
        member_name: message.member_name,
        type: message.type,
        sent_at: message.sent_at,
      });
      return { isNew: false, pushed: false, processed: false };
    }
  }

  async start() {
    if (this.isRunning) return this.loopPromise;

    try {
      if (!this.accountId) {
        await this.loadStorageState();
        await this.loadAccessTokenState();
        await this.startSessionFileWatcher();
      }

      if (!this.sessionCookie) {
        console.warn(`${this.accountId ? `[${this.accountName}] ` : ''}未检测到有效会话凭据，等待配置`);
      } else {
        try {
          await this.refreshAccessToken({ sessionActivation: false });
        } catch (error) {
          console.warn(`${this.accountId ? `[${this.accountName}] ` : ''}初始令牌刷新失败，等待新会话或重试:`, error.message);
        }
      }
    } catch (error) {
      console.error(`${this.accountId ? `[${this.accountName}] ` : ''}监控服务初始化失败:`, error.message);
      throw error;
    }

    console.log(`${this.accountId ? `[${this.accountName}] ` : ''}Nogi monitor started (Pure API mode, poll interval: ${Math.round(this.pollIntervalMs / 1000)}s)`);
    this.isRunning = true;
    this.authState = this.accessToken ? 'authenticated' : 'starting';
    this.loopPromise = this.runLoop();
    return this.loopPromise;
  }

  async runLoop() {
    try {
      let loopCount = 0;
      while (this.isRunning) {
        await this.waitForPollingAllowed();
        if (!this.isRunning) break;

        try {
          const polling = (async () => {
            if (this.shouldRefreshAccessToken()) {
              await this.refreshAccessToken();
            }
            await this.poll();
          })();
          this.activePollingPromise = polling;
          await polling;

          loopCount++;
        } catch (error) {
          const isAuthError = error.message?.includes('Nogi API 401')
            || error.message?.includes('400 Bad Request')
            || error.message?.includes('未配置会话')
            || error.message?.includes('会话已过期');

          await recordError('monitor.poll', error, {
            mode: 'pure_api',
            account_id: this.accountId,
            has_access_token: Boolean(this.accessToken),
            is_auth_error: isAuthError,
            consecutive_failures: this.consecutiveAuthFailures,
          });

          if (isAuthError) {
            this.consecutiveAuthFailures += 1;
            if (this.onStateChange) {
              await this.onStateChange({
                consecutiveFailures: this.consecutiveAuthFailures,
                lastError: error.message,
              }).catch(() => {});
            }

            if (this.consecutiveAuthFailures >= this.maxConsecutiveAuthFailures) {
              await this.pauseAuthentication(error);
              await this.waitForAuthenticationResume();
              continue;
            }

            console.error(
              `${this.accountId ? `[${this.accountName}] ` : ''}认证请求失败；/v2/update_token 连续失败 `
              + `${this.consecutiveAuthFailures}/${this.maxConsecutiveAuthFailures} 次，继续重试。`,
            );
            await sleep(Math.max(60_000, this.pollIntervalMs));
            continue;
          }
        } finally {
          this.activePollingPromise = null;
        }
        if (this.isRunning) await sleep(this.pollIntervalMs);
      }
    } finally {
      this.loopPromise = null;
    }
  }

  async stop() {
    this.isRunning = false;
    if (this.signedOutLogTimer) clearInterval(this.signedOutLogTimer);
    this.signedOutLogTimer = null;
    this.releaseAuthenticationWaiters();
    this.releaseSessionReloadWaiters();
    this.stopSessionFileWatcher();
    if (this.loopPromise) await this.loopPromise;
  }

  async clearPersistedAccessToken() {
    this.lastPersistedAccessToken = '';
    if (!this.accountId) {
      try {
        await fs.unlink(this.accessTokenStateFile);
      } catch (error) {
        if (error.code !== 'ENOENT') {
          console.warn('无法清除已失效的访问令牌缓存:', error.message);
        }
      }
    }
  }

  async enterSignedOut(cause) {
    if (this.authState === 'signedOut') return;
    this.authState = 'signedOut';
    console.error(`[NOGI_AUTH_SIGNED_OUT] ${this.accountId ? `[${this.accountName}] ` : ''}/v2/update_token 返回 400，会话已退出。`);
    if (this.onStateChange) {
      await this.onStateChange({
        status: 'expired',
        lastError: cause?.message || '官网会话已失效 (400 Bad Request)',
      }).catch(() => {});
    }
    await this.pauseAuthentication(cause, { auth_state: 'signedOut' });
    if (!this.signedOutLogTimer) {
      this.signedOutLogTimer = setInterval(() => {
        if (this.authState === 'signedOut') {
          console.error(`[NOGI_SESSION_UPDATE_REQUIRED] ${this.accountId ? `[${this.accountName}] ` : ''}会话已退出，需要更新会话凭据。`);
        }
      }, 5 * 60_000);
      this.signedOutLogTimer.unref?.();
    }
  }

  async pauseAuthentication(cause, context = {}) {
    const wasPaused = this.authPaused;
    this.authPaused = true;
    this.accessToken = '';
    this.observedTokenAt = 0;
    await this.clearPersistedAccessToken();

    if (this.onStateChange) {
      await this.onStateChange({
        status: this.authState === 'signedOut' ? 'expired' : 'error',
        lastError: cause?.message || '鉴权暂停',
      }).catch(() => {});
    }

    if (!wasPaused) {
      const message = [
        `[NOGI_AUTH_PAUSED] ${this.accountId ? `[${this.accountName}] ` : ''}官网 access token 已失效，且无法通过 API 自动刷新。`,
        '已暂停消息轮询；管理后台及其他账号保持运行。',
        '请更新该账号的会话凭据；验证通过后将自动恢复。',
      ].join('\n');
      console.error(message);
      await recordError('monitor.auth_paused', cause || new Error(message), {
        account_id: this.accountId,
        requires_session_update: true,
        consecutive_auth_failures: this.consecutiveAuthFailures,
        ...context,
      });
    }
  }

  resumeAuthentication() {
    this.authPaused = false;
    this.authState = 'authenticated';
    this.consecutiveAuthFailures = 0;
    if (this.signedOutLogTimer) {
      clearInterval(this.signedOutLogTimer);
      this.signedOutLogTimer = null;
    }
    this.releaseAuthenticationWaiters();
    if (this.onStateChange) {
      this.onStateChange({
        status: 'active',
        consecutiveFailures: 0,
        lastError: null,
      }).catch(() => {});
    }
  }

  waitForAuthenticationResume() {
    if (!this.isRunning || !this.authPaused) return Promise.resolve();
    return new Promise(resolve => this.authResumeWaiters.add(resolve));
  }

  releaseAuthenticationWaiters() {
    for (const waiter of this.authResumeWaiters) waiter();
    this.authResumeWaiters.clear();
  }

  async waitForPollingAllowed() {
    if (this.isReloadingSession) {
      await new Promise(resolve => this.sessionReloadWaiters.add(resolve));
    }
    if (this.authPaused) {
      await this.waitForAuthenticationResume();
    }
  }

  releaseSessionReloadWaiters() {
    for (const waiter of this.sessionReloadWaiters) waiter();
    this.sessionReloadWaiters.clear();
  }

  shouldRefreshAccessToken() {
    if (!this.accessToken) return true;
    const expiresAt = tokenExpiry(this.accessToken);
    return expiresAt == null || expiresAt.getTime() <= Date.now() + ACCESS_TOKEN_REFRESH_SKEW_MS;
  }

  async refreshAccessToken({ sessionActivation = false } = {}) {
    this.assertAuthAllowed({ sessionActivation });
    if (!this.sessionCookie) {
      throw new Error('未配置会话 Cookie (sessionCookie)，无法续期访问令牌');
    }

    if (this.refreshPromise) return this.refreshPromise;

    this.refreshPromise = (async () => {
      console.log('正在通过官网 API 续期访问令牌...');
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), this.requestTimeoutMs);

      try {
        const response = await fetch(`${this.apiUrl}/v2/update_token`, {
          method: 'POST',
          headers: {
            Accept: 'application/json',
            'Content-Type': 'application/json',
            Origin: this.webUrl,
            Referer: `${this.webUrl}/`,
            'X-Talk-App-ID': this.appId,
            'X-Talk-App-Platform': this.platform,
            'Accept-Language': process.env.NOGI_ACCEPT_LANGUAGE || 'zh-CN,en-US,ja',
            Cookie: `session=${this.sessionCookie}`,
          },
          body: JSON.stringify({}),
          signal: controller.signal,
        });

        if (response.status === 400) {
          await this.enterSignedOut(new Error('/v2/update_token returned HTTP 400'));
          throw new Error('官网会话已失效 (400 Bad Request)，请重新登录并上传新会话');
        }

        if (!response.ok) {
          const errText = await response.text().catch(() => '');
          this.consecutiveAuthFailures += 1;
          throw new Error(`/v2/update_token 失败 (${response.status}): ${errText}`);
        }

        const data = await response.json();
        if (!data.access_token) {
          throw new Error('/v2/update_token 响应未包含 access_token');
        }

        this.accessToken = data.access_token;
        this.observedTokenAt = Date.now();
        this.consecutiveAuthFailures = 0;

        // 检查 Set-Cookie 中是否轮转了 session cookie
        const setCookieHeader = response.headers.get('set-cookie');
        if (setCookieHeader) {
          const match = setCookieHeader.match(/session=([a-zA-Z0-9_-]+)/);
          if (match && match[1] && match[1] !== this.sessionCookie) {
            this.sessionCookie = match[1];
            console.log(`${this.accountId ? `[${this.accountName}] ` : ''}检测到官网 session Cookie 轮转，已自动更新`);
          }
        }

        if (this.onStateChange) {
          await this.onStateChange({
            status: 'active',
            sessionCookie: this.sessionCookie,
            accessToken: this.accessToken,
            tokenExpiresAt: tokenExpiry(this.accessToken),
            consecutiveFailures: 0,
            lastError: null,
          }).catch(err => console.warn(`[${this.accountName}] 更新状态回调失败:`, err.message));
        }

        if (!this.accountId) {
          await this.persistSession();
          await this.persistAccessToken();
        }
        console.log(`${this.accountId ? `[${this.accountName}] ` : ''}✓ 官网访问令牌续期成功 (有效期约 ${data.expires_in || 3600} 秒)`);
        return this.accessToken;
      } catch (error) {
        console.error(`${this.accountId ? `[${this.accountName}] ` : ''}官网访问令牌续期失败:`, error.message);
        throw error;
      } finally {
        clearTimeout(timeout);
      }
    })().finally(() => {
      this.refreshPromise = null;
    });

    return this.refreshPromise;
  }

  // 兼容别名
  async refreshFrontendSession(options) {
    return this.refreshAccessToken(options);
  }

  headers() {
    if (!this.accessToken) throw new Error('尚未获取到有效访问令牌');
    return {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'X-Talk-App-ID': this.appId,
      'X-Talk-App-Platform': this.platform,
      'Accept-Language': process.env.NOGI_ACCEPT_LANGUAGE || 'zh-CN,en-US,ja',
      Authorization: `Bearer ${this.accessToken}`,
    };
  }

  async apiRequest(pathname, { retryAuth = true, sessionActivation = false } = {}) {
    this.assertAuthAllowed({ sessionActivation });

    if (retryAuth && this.shouldRefreshAccessToken()) {
      try {
        await this.refreshAccessToken();
      } catch (error) {
        console.warn('官网访问令牌预刷新失败,继续使用当前令牌请求:', error.message);
      }
    }

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.requestTimeoutMs);
    let response;
    try {
      response = await fetch(`${this.apiUrl}${pathname}`, {
        headers: this.headers(),
        signal: controller.signal,
      });
    } finally {
      clearTimeout(timeout);
    }

    if (response.status === 401 && retryAuth) {
      try {
        await this.refreshAccessToken({ sessionActivation });
        return this.apiRequest(pathname, { retryAuth: false, sessionActivation });
      } catch (refreshError) {
        console.error('会话刷新失败:', refreshError.message);
        const detail = '会话已过期,无法刷新。请上传新的会话文件。';
        throw new Error(`Nogi API ${response.status} ${pathname}: ${detail}`);
      }
    }

    const responseText = await response.text();
    let payload = null;
    if (responseText) {
      try {
        payload = JSON.parse(responseText);
      } catch {
        payload = responseText;
      }
    }
    if (!response.ok) {
      const detail = typeof payload === 'string' ? payload : payload?.message || payload?.error;
      throw new Error(`Nogi API ${response.status} ${pathname}${detail ? `: ${detail}` : ''}`);
    }
    return payload;
  }

  async resolveGroups() {
    if (this.groupIds.length > 0) {
      return this.groupIds.map(id => ({ id, name: '', phone_image: null, thumbnail: null }));
    }

    const groups = await this.apiRequest(`/v2/groups?organization_id=${encodeURIComponent(this.organizationId)}`);
    if (!Array.isArray(groups)) throw new Error('Nogi API groups response is not an array');

    return groups
      .filter(group => String(group.organization_id) === String(this.organizationId))
      .filter(group => group.state === 'open')
      .filter(group => group.subscription?.state === 'active')
      .map(group => ({
        id: Number(group.id),
        name: String(group.name || '').trim(),
        phone_image: group.phone_image || null,
        thumbnail: group.thumbnail || null,
      }))
      .filter(group => Number.isInteger(group.id));
  }

  async fetchTimelinePage(groupId, continuation = null) {
    const query = new URLSearchParams();
    if (continuation == null) {
      query.set('count', '200');
      query.set('order', 'desc');
    } else {
      query.set('continuation', String(continuation));
    }
    query.set('clear_unread', 'false');

    const payload = await this.apiRequest(`/v2/groups/${encodeURIComponent(groupId)}/timeline?${query}`);
    if (!payload || !Array.isArray(payload.messages)) {
      throw new Error(`Nogi API timeline response for group ${groupId} is invalid`);
    }
    return {
      messages: payload.messages,
      continuation: payload.continuation == null || payload.continuation === ''
        ? null
        : String(payload.continuation),
    };
  }

  async fetchTimeline(groupId) {
    const page = await this.fetchTimelinePage(groupId);
    return page.messages;
  }

  async fetchAllTimeline(groupId) {
    const firstPage = await this.fetchTimelinePage(groupId);
    const messages = [...firstPage.messages];
    const seenContinuations = new Set();
    let continuation = firstPage.continuation;
    let pageCount = 1;

    while (continuation != null) {
      if (seenContinuations.has(continuation)) {
        throw new Error(`Nogi API timeline continuation loop detected for group ${groupId}`);
      }
      seenContinuations.add(continuation);

      const page = await this.fetchTimelinePage(groupId, continuation);
      messages.push(...page.messages);
      continuation = page.continuation;
      pageCount += 1;
    }

    return {
      messages,
      firstPageMessages: firstPage.messages,
      pageCount,
    };
  }

  async fetchPastMessages(groupId) {
    const payload = await this.apiRequest(
      `/v2/groups/${encodeURIComponent(groupId)}/past_messages`,
    );
    if (!payload || !Array.isArray(payload.messages)) {
      throw new Error(`Nogi API past_messages response for group ${groupId} is invalid`);
    }
    return payload.messages;
  }

  async poll() {
    this.assertAuthAllowed();
    const isInitialSync = !this.hasCompletedInitialPoll;
    const groups = await this.resolveGroups();
    if (groups.length === 0) throw new Error('No active subscribed groups found');

    const activeGroupIds = new Set(groups.map(group => group.id));
    for (const groupId of this.backfilledGroupIds) {
      if (!activeGroupIds.has(groupId)) this.backfilledGroupIds.delete(groupId);
    }
    const hasHistoryBackfill = groups.some(group => !this.backfilledGroupIds.has(group.id));
    let pollTimeout;
    const timeoutPromise = hasHistoryBackfill
      ? null
      : new Promise((_, reject) => {
        pollTimeout = setTimeout(() => reject(new Error('轮询超时(120秒)')), 120_000);
      });

    try {
      const polling = (async () => {
        let fetched = 0;
        let stored = 0;
        let pushed = 0;

        for (const group of groups) {
          const shouldBackfillHistory = !this.backfilledGroupIds.has(group.id);
          const sendPush = shouldBackfillHistory ? !this.backfillOnStart : true;
          let rawMessages;
          let currentPageMessages;
          if (shouldBackfillHistory) {
            const pastMessages = await this.fetchPastMessages(group.id);
            const timeline = await this.fetchAllTimeline(group.id);
            const seenIds = new Set();
            rawMessages = [...timeline.messages, ...pastMessages].filter(rawMessage => {
              const rawId = String(rawMessage.id ?? rawMessage.message_id ?? '');
              if (!rawId || seenIds.has(rawId)) return false;
              seenIds.add(rawId);
              return true;
            });
            currentPageMessages = timeline.firstPageMessages;
            const backfillReason = isInitialSync
              ? 'startup'
              : this.historyBackfillReason || 'new_subscription';
            console.log(
              `Nogi history fetched: reason=${backfillReason}, group=${group.id}, `
              + `timeline_pages=${timeline.pageCount}, `
              + `timeline_messages=${timeline.messages.length}, past_messages=${pastMessages.length}, `
              + `unique_messages=${rawMessages.length}`,
            );
          } else {
            rawMessages = await this.fetchTimeline(group.id);
            currentPageMessages = rawMessages;
          }

          const previousIds = this.groupMessageIds.get(group.id) || new Set();
          const currentIds = new Set(
            currentPageMessages.map(rawMessage => String(rawMessage.id ?? rawMessage.message_id)),
          );
          const newMessages = rawMessages.filter(rawMessage => !previousIds.has(String(rawMessage.id ?? rawMessage.message_id)));
          const failedIds = new Set();
          fetched += newMessages.length;
          for (const rawMessage of newMessages.reverse()) {
            const rawId = String(rawMessage.id ?? rawMessage.message_id);
            const message = this.normalizeMessage(rawMessage, group);
            if (!message) {
              previousIds.add(rawId);
              continue;
            }
            const result = await this.processMessage(message, sendPush);
            stored += result.isNew ? 1 : 0;
            pushed += result.pushed ? 1 : 0;
            if (result.processed) previousIds.add(rawId);
            else failedIds.add(rawId);
          }
          this.groupMessageIds.set(
            group.id,
            new Set([...currentIds].filter(id => !failedIds.has(id))),
          );
          if (shouldBackfillHistory) {
            if (failedIds.size > 0) {
              throw new Error(
                `Nogi history persistence failed for group ${group.id}: ${failedIds.size} message(s)`,
              );
            }
            this.backfilledGroupIds.add(group.id);
          }
        }

        this.hasCompletedInitialPoll = true;
        if (groups.every(group => this.backfilledGroupIds.has(group.id))) {
          this.historyBackfillReason = null;
        }
        if (this.onStateChange) {
          await this.onStateChange({
            status: 'active',
            lastSyncAt: new Date(),
            subscribedGroups: groups.map(g => ({ id: g.id, name: g.name })),
            consecutiveFailures: 0,
            lastError: null,
          }).catch(err => console.warn(`[${this.accountName}] 更新同步状态失败:`, err.message));
        }
        console.log(`${this.accountId ? `[${this.accountName}] ` : ''}Nogi monitor poll complete: groups=${groups.length}, fetched=${fetched}, stored=${stored}, pushed=${pushed}`);
      })();

      if (timeoutPromise) await Promise.race([polling, timeoutPromise]);
      else await polling;
    } finally {
      clearTimeout(pollTimeout);
    }
  }

  async loadStorageState() {
    try {
      const { state, credentials } = await readBrowserSession(this.storageStateFile);
      if (credentials?.sessionCookie) {
        this.sessionCookie = credentials.sessionCookie;
      }
      if (credentials?.accessToken && !this.accessToken) {
        this.accessToken = credentials.accessToken;
        this.observedTokenAt = Date.now();
      }
      return state;
    } catch (error) {
      if (error.code !== 'ENOENT') {
        console.warn('会话文件无法读取:', error.message);
      }
      return null;
    }
  }

  async loadAccessTokenState() {
    try {
      const state = JSON.parse(await fs.readFile(this.accessTokenStateFile, 'utf8'));
      const token = String(state.accessToken || '').trim();
      const expiresAt = tokenExpiry(token);
      if (!token || (expiresAt && expiresAt.getTime() <= Date.now() + ACCESS_TOKEN_REFRESH_SKEW_MS)) return '';
      if (!this.accessToken) {
        this.accessToken = token;
        this.observedTokenAt = Date.now();
      }
      return token;
    } catch (error) {
      if (error.code !== 'ENOENT') console.warn('访问令牌缓存无法读取:', error.message);
      return '';
    }
  }

  async persistAccessToken() {
    if (!this.accessToken || this.accessToken === this.lastPersistedAccessToken) return;
    const token = this.accessToken;
    this.lastPersistedAccessToken = token;
    try {
      const directory = path.dirname(this.accessTokenStateFile);
      await fs.mkdir(directory, { recursive: true });
      const tempFile = `${this.accessTokenStateFile}.tmp-${process.pid}-${Date.now()}`;
      await fs.writeFile(tempFile, JSON.stringify({
        accessToken: token,
        savedAt: Date.now(),
      }), { mode: 0o600 });
      await fs.rename(tempFile, this.accessTokenStateFile);
    } catch (error) {
      if (this.lastPersistedAccessToken === token) this.lastPersistedAccessToken = '';
      console.warn('访问令牌缓存持久化失败:', error.message);
    }
  }

  async persistSession() {
    if (!this.sessionCookie) return false;
    try {
      let stateToSave;
      const existing = await readJsonIfExists(this.storageStateFile).catch(() => null);
      if (existing && Array.isArray(existing.cookies)) {
        // 保留旧版 storageState 结构
        for (const c of existing.cookies) {
          if (c.name === 'session') c.value = this.sessionCookie;
        }
        stateToSave = existing;
      } else {
        // 保存新版轻量凭据
        stateToSave = {
          sessionCookie: this.sessionCookie,
          accessToken: this.accessToken,
          updatedAt: new Date().toISOString(),
        };
      }
      const serialized = JSON.stringify(stateToSave, null, 2);
      const version = sessionVersion(serialized);
      this.lastPersistedStorageVersion = version;
      await atomicWritePrivateFile(this.storageStateFile, serialized);
      return true;
    } catch (error) {
      console.warn('会话状态持久化失败:', error.message);
      return false;
    }
  }

  async startSessionFileWatcher() {
    if (this.sessionFileWatcher) return;

    const directory = path.dirname(this.storageStateFile);
    const stateFileName = path.basename(this.storageStateFile);
    const { uploadStatusFilePath } = browserSessionPaths(this.storageStateFile);
    const uploadStatusFileName = path.basename(uploadStatusFilePath);
    await fs.mkdir(directory, { recursive: true });

    try {
      const initialSession = await readBrowserSession(this.storageStateFile);
      this.lastPersistedStorageVersion = initialSession.version;
      const initialUpload = await readJsonIfExists(uploadStatusFilePath).catch(() => null);
      if (initialUpload?.requestId && initialUpload.version === initialSession.version) {
        this.lastHandledUploadRequestId = initialUpload.requestId;
      }
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }

    this.sessionFileWatcher = watch(directory, { persistent: false })
      .on('change', (eventType, filename) => {
        if (filename && ![stateFileName, uploadStatusFileName].includes(String(filename))) return;
        if (eventType !== 'change' && eventType !== 'rename') return;
        if (this.sessionReloadTimer) clearTimeout(this.sessionReloadTimer);
        this.sessionReloadTimer = setTimeout(() => {
          this.sessionReloadTimer = null;
          this.handleSessionFileChange().catch(error => {
            console.warn('处理会话文件更新失败:', error.message);
          });
        }, 100);
      })
      .on('error', (error) => {
        console.warn('会话文件监听器错误:', error.message);
        this.sessionFileWatcher = null;
      });

    console.log(`开始监听会话文件目录: ${directory}`);
    queueMicrotask(() => {
      this.handleSessionFileChange().catch(error => {
        console.warn('检查待激活会话失败:', error.message);
      });
    });
  }

  async handleSessionFileChange() {
    let loadedSession;
    try {
      loadedSession = await readBrowserSession(this.storageStateFile);
    } catch (error) {
      if (error.code !== 'ENOENT') console.warn('检查会话文件失败:', error.message);
      return;
    }
    const { uploadStatusFilePath } = browserSessionPaths(this.storageStateFile);
    const upload = await readJsonIfExists(uploadStatusFilePath).catch(() => null);
    const matchingUpload = upload?.requestId && upload.version === loadedSession.version
      ? upload
      : null;
    if (matchingUpload?.requestId === this.lastHandledUploadRequestId) return;
    if (!matchingUpload && loadedSession.version === this.lastPersistedStorageVersion) return;
    loadedSession.requestId = matchingUpload?.requestId || null;
    console.log('检测到外部会话文件更新,准备重载凭据...');
    await this.reloadSession(loadedSession);
  }

  stopSessionFileWatcher() {
    if (this.sessionReloadTimer) {
      clearTimeout(this.sessionReloadTimer);
      this.sessionReloadTimer = null;
    }
    if (this.sessionFileWatcher) {
      this.sessionFileWatcher.close();
      this.sessionFileWatcher = null;
      console.log('停止监听会话文件');
    }
  }

  async reloadSession(loadedSession = null) {
    if (this.isReloadingSession) {
      this.pendingSessionReload = true;
      console.log('会话重载已在进行中,将在完成后处理最新版本');
      return;
    }

    this.isReloadingSession = true;
    const activePolling = this.activePollingPromise;
    if (activePolling) await activePolling.catch(() => {});
    let requestedVersion = loadedSession?.version || null;
    let requestId = loadedSession?.requestId || null;

    try {
      console.log('开始重载会话凭据...');
      const requestedSession = loadedSession || await readBrowserSession(this.storageStateFile);
      const newStorageState = requestedSession.state;
      requestedVersion = requestedSession.version;
      requestId = requestedSession.requestId || requestId;
      if (requestId) this.lastHandledUploadRequestId = requestId;

      if (!newStorageState) {
        console.warn('无法加载新会话文件,保持当前会话');
        return;
      }

      const credentials = requestedSession.credentials || extractSessionCredentials(newStorageState);
      if (!credentials?.sessionCookie) {
        throw new Error('新会话文件中未找到有效的 session Cookie');
      }

      const { activationStatusFilePath } = browserSessionPaths(this.storageStateFile);
      await atomicWritePrivateJson(activationStatusFilePath, {
        requestId,
        version: requestedVersion,
        status: 'activating',
        updatedAt: new Date().toISOString(),
      });

      this.sessionCookie = credentials.sessionCookie;
      this.accessToken = credentials.accessToken || '';
      this.observedTokenAt = credentials.accessToken ? Date.now() : 0;
      this.lastPersistedAccessToken = '';
      this.refreshPromise = null;

      console.log('正在验证新会话凭据...');
      await this.refreshAccessToken({ sessionActivation: true });
      await this.apiRequest(
        `/v2/groups?organization_id=${encodeURIComponent(this.organizationId)}`,
        { retryAuth: false, sessionActivation: true },
      );

      this.resumeAuthentication();
      this.backfilledGroupIds.clear();
      this.historyBackfillReason = 'session_reload';
      console.log('会话验证成功，已调度消息回填');

      await atomicWritePrivateJson(activationStatusFilePath, {
        requestId,
        version: requestedVersion,
        status: 'active',
        updatedAt: new Date().toISOString(),
      });

      console.log(`✓ 官网会话已激活并验证: ${requestedVersion}`);
    } catch (error) {
      console.error('重载会话失败:', error.message);
      if (requestedVersion) {
        const { activationStatusFilePath } = browserSessionPaths(this.storageStateFile);
        await atomicWritePrivateJson(activationStatusFilePath, {
          requestId,
          version: requestedVersion,
          status: 'failed',
          updatedAt: new Date().toISOString(),
          error: error.message,
        }).catch(() => {});
      }
      await recordError('monitor.reload_session', error);
      await this.pauseAuthentication(error, {
        request_id: requestId,
        session_version: requestedVersion,
      });
    } finally {
      this.isReloadingSession = false;
      this.releaseSessionReloadWaiters();
      if (this.pendingSessionReload) {
        this.pendingSessionReload = false;
        queueMicrotask(() => {
          this.handleSessionFileChange().catch(error => {
            console.warn('处理排队的会话文件更新失败:', error.message);
          });
        });
      }
    }
  }

  async updateCredentials({ sessionCookie, accessToken = null, tokenExpiresAt = null }) {
    if (sessionCookie) this.sessionCookie = sessionCookie;
    if (accessToken) {
      this.accessToken = accessToken;
      this.observedTokenAt = Date.now();
    }
    this.consecutiveAuthFailures = 0;
    this.resumeAuthentication();
    this.backfilledGroupIds.clear();
    this.historyBackfillReason = 'credentials_update';

    if (!this.accessToken && this.sessionCookie) {
      try {
        await this.refreshAccessToken({ sessionActivation: true });
      } catch (err) {
        console.warn(`${this.accountId ? `[${this.accountName}] ` : ''}更新凭据后刷新令牌失败:`, err.message);
      }
    }
  }

  async triggerPoll() {
    if (this.authPaused) {
      await this.refreshAccessToken({ sessionActivation: true });
      this.resumeAuthentication();
    }
    return this.poll();
  }
}

const monitor = new NogiBrowserMonitor();

export { NogiBrowserMonitor, NogiBrowserMonitor as NogiMonitor };
export default monitor;

if (import.meta.url === `file://${process.argv[1]}`) {
  const { default: mediaServer } = await import('./media-server.js');
  mediaServer.start().then(() => monitor.start()).catch(error => {
    void recordError('monitor.start', error);
    process.exitCode = 1;
  });

  const shutdown = async () => {
    await monitor.stop();
    await mediaServer.stop();
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}
