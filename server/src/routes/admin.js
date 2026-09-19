import express from 'express';
import fs from 'fs/promises';
import { randomUUID } from 'node:crypto';
import { readPersistedErrorLogs, recordError } from '../services/error-log.js';
import {
  atomicWritePrivateFile,
  atomicWritePrivateJson,
  browserSessionPaths,
  extractSessionCredentials,
  readJsonIfExists,
  sessionVersion,
} from '../services/browser-session.js';
import {
  listAccounts,
  getAccount,
  createAccount,
  updateAccountInfo,
  updateAccountCredentials,
  deleteAccount,
  validateOfficialSession,
  touchAccountSignal,
} from '../services/account-service.js';
import { queryOne, queryAll } from '../db/index.js';

const router = express.Router();
let sessionWriteQueue = Promise.resolve();

function parseTimestamp(value) {
  if (value == null || value === '') return null;
  const timestamp = new Date(String(value));
  return Number.isNaN(timestamp.getTime()) ? null : timestamp.toISOString();
}

/**
 * GET /v1/admin/error-logs
 * 从挂载的卷中读取经过脱敏的持久化错误日志。
 */
router.get('/error-logs', async (req, res) => {
  const requestedLimit = req.query.limit == null ? 100 : Number(req.query.limit);
  if (!Number.isInteger(requestedLimit) || requestedLimit < 1 || requestedLimit > 500) {
    return res.status(400).json({
      success: false,
      error: 'limit must be an integer between 1 and 500',
    });
  }

  const since = parseTimestamp(req.query.since);
  const before = parseTimestamp(req.query.before);
  if ((req.query.since && !since) || (req.query.before && !before)) {
    return res.status(400).json({
      success: false,
      error: 'since and before must be valid timestamps',
    });
  }
  if (since && before && Date.parse(since) >= Date.parse(before)) {
    return res.status(400).json({
      success: false,
      error: 'since must be earlier than before',
    });
  }

  const level = String(req.query.level || '').trim();
  const scope = String(req.query.scope || '').trim();
  const query = String(req.query.q || '').trim();
  if (level.length > 20 || scope.length > 255 || query.length > 200) {
    return res.status(400).json({
      success: false,
      error: 'one or more filters are too long',
    });
  }

  try {
    const logs = await readPersistedErrorLogs({
      limit: requestedLimit,
      level,
      scope,
      query,
      since,
      before,
    });
    res.json({
      success: true,
      source: 'persistent-file',
      count: logs.length,
      logs,
      nextBefore: logs.length === requestedLimit ? logs.at(-1)?.created_at || null : null,
    });
  } catch (error) {
    await recordError('server.admin.read_error_logs', error);
    res.status(500).json({
      success: false,
      error: 'Unable to read persistent error logs',
    });
  }
});

/**
 * POST /v1/admin/browser-session
 * 无需重新部署即可上传新的浏览器会话状态
 */
router.post('/browser-session', async (req, res) => {
  try {
    const { session } = req.body;

    if (!session || typeof session !== 'object') {
      return res.status(400).json({
        success: false,
        error: 'Missing or invalid session data. Expected JSON object with cookies, origins, and localStorage.',
      });
    }

    // 校验会话结构：支持纯 API 会话凭据或旧版浏览器快照
    const credentials = extractSessionCredentials(session);
    const hasLegacyStructure = Array.isArray(session.cookies) && Array.isArray(session.origins);

    if (!credentials && !hasLegacyStructure) {
      return res.status(400).json({
        success: false,
        error: 'Invalid session structure: expected either clean session credentials (sessionCookie) or legacy browser storageState',
      });
    }

    // 从环境变量获取状态文件路径，否则使用默认值
    const stateFilePath = process.env.NOGI_BROWSER_STATE_FILE || '/data/nogi-browser-state.json';
    const { uploadStatusFilePath, activationStatusFilePath } = browserSessionPaths(stateFilePath);
    const serializedSession = JSON.stringify(session, null, 2);
    const version = sessionVersion(serializedSession);
    const requestId = randomUUID();
    const uploadedAt = new Date().toISOString();

    const writeSession = async () => {
      await atomicWritePrivateFile(stateFilePath, serializedSession);
      await atomicWritePrivateJson(uploadStatusFilePath, { requestId, version, uploadedAt });
    };
    sessionWriteQueue = sessionWriteQueue.then(writeSession, writeSession);
    await sessionWriteQueue;

    const activation = await readJsonIfExists(activationStatusFilePath).catch(() => null);
    const activated = activation?.requestId === requestId && activation?.status === 'active';

    if (credentials?.sessionCookie) {
      try {
        await updateAccountCredentials('acc_default', {
          sessionCookie: credentials.sessionCookie,
          accessToken: credentials.accessToken || null,
          tokenExpiresAt: credentials.expiresAt || null,
          status: 'active',
        });
      } catch (err) {
        console.warn('同步旧版会话至 acc_default 失败:', err.message);
      }
    }

    console.log(`Browser session updated: ${stateFilePath}`);

    res.status(activated ? 200 : 202).json({
      success: true,
      accepted: true,
      activated,
      activationStatus: activated ? 'active' : 'pending',
      requestId,
      version,
      message: activated
        ? 'Browser session is already active.'
        : 'Browser session uploaded. Monitor activation is pending.',
      path: stateFilePath,
      timestamp: uploadedAt,
    });
  } catch (error) {
    await recordError('server.admin.upload_browser_session', error);
    res.status(500).json({
      success: false,
      error: error.message,
    });
  }
});

/**
 * GET /v1/admin/browser-session/status
 * 检查浏览器会话文件是否存在以及最后修改时间
 */
router.get('/browser-session/status', async (req, res) => {
  try {
    const stateFilePath = process.env.NOGI_BROWSER_STATE_FILE || '/data/nogi-browser-state.json';
    const { uploadStatusFilePath, activationStatusFilePath } = browserSessionPaths(stateFilePath);

    try {
      const [stats, upload, activation] = await Promise.all([
        fs.stat(stateFilePath),
        readJsonIfExists(uploadStatusFilePath),
        readJsonIfExists(activationStatusFilePath),
      ]);
      const activated = Boolean(
        upload?.requestId
        && activation?.requestId === upload.requestId
        && activation?.status === 'active',
      );
      res.json({
        success: true,
        exists: true,
        path: stateFilePath,
        size: stats.size,
        lastModified: stats.mtime.toISOString(),
        lastAccessed: stats.atime.toISOString(),
        uploadedVersion: upload?.version || null,
        uploadedAt: upload?.uploadedAt || null,
        activeVersion: activation?.status === 'active' ? activation.version : null,
        activeRequestId: activation?.status === 'active' ? activation.requestId || null : null,
        requestId: upload?.requestId || null,
        activated,
        activationStatus: !upload?.requestId
          ? 'unknown'
          : activation?.requestId === upload.requestId
            ? activation.status
            : 'pending',
        activationUpdatedAt: activation?.updatedAt || null,
        activationError: activation?.requestId === upload?.requestId && activation?.status === 'failed'
          ? activation.error || null
          : null,
      });
    } catch (error) {
      if (error.code === 'ENOENT') {
        res.json({
          success: true,
          exists: false,
          path: stateFilePath,
          message: 'Browser session file not found',
        });
      } else {
        throw error;
      }
    }
  } catch (error) {
    await recordError('server.admin.check_browser_session', error);
    res.status(500).json({
      success: false,
      error: error.message,
    });
  }
});

/**
 * GET /v1/admin/overview
 * 获取仪表盘核心指标概览
 */
router.get('/overview', async (req, res) => {
  try {
    const [
      accountCounts,
      messageStats,
      deviceStats,
      recentErrors,
    ] = await Promise.all([
      queryOne(`
        SELECT
          COUNT(*)::int AS total,
          COUNT(*) FILTER (WHERE status = 'active')::int AS active,
          COUNT(*) FILTER (WHERE status = 'warning' OR consecutive_failures > 0)::int AS warning,
          COUNT(*) FILTER (WHERE status = 'expired' OR status = 'error')::int AS expired
        FROM accounts
      `).catch(() => ({ total: 0, active: 0, warning: 0, expired: 0 })),
      queryOne(`
        SELECT
          COUNT(*)::int AS total,
          COUNT(*) FILTER (WHERE sent_at >= CURRENT_DATE)::int AS today,
          COUNT(*) FILTER (WHERE type IN ('image', 'audio', 'video'))::int AS media
        FROM messages
      `).catch(() => ({ total: 0, today: 0, media: 0 })),
      queryOne(`
        SELECT
          COUNT(*)::int AS total,
          COUNT(*) FILTER (WHERE platform = 'android')::int AS android,
          COUNT(*) FILTER (WHERE platform = 'ios')::int AS ios
        FROM devices
      `).catch(() => ({ total: 0, android: 0, ios: 0 })),
      queryOne(`
        SELECT COUNT(*)::int AS count
        FROM error_logs
        WHERE created_at >= NOW() - INTERVAL '24 hours'
      `).catch(() => ({ count: 0 })),
    ]);

    const mem = process.memoryUsage();
    res.json({
      success: true,
      data: {
        accounts: accountCounts || { total: 0, active: 0, warning: 0, expired: 0 },
        messages: messageStats || { total: 0, today: 0, media: 0 },
        devices: deviceStats || { total: 0, android: 0, ios: 0 },
        errors24h: recentErrors?.count || 0,
        system: {
          uptime: Math.floor(process.uptime()),
          memoryRssMb: Math.round(mem.rss / 1024 / 1024),
          memoryHeapMb: Math.round(mem.heapUsed / 1024 / 1024),
          nodeVersion: process.version,
          platform: process.platform,
        },
      },
    });
  } catch (error) {
    await recordError('server.admin.overview', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * GET /v1/admin/accounts
 * 获取所有账号列表
 */
router.get('/accounts', async (req, res) => {
  try {
    const accounts = await listAccounts();
    res.json({ success: true, data: accounts });
  } catch (error) {
    await recordError('server.admin.list_accounts', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * POST /v1/admin/accounts
 * 新建账号并验证会话凭据
 */
router.post('/accounts', async (req, res) => {
  try {
    const { id, name, sessionCookie, session, metadata } = req.body;
    let cookie = sessionCookie;

    if (!cookie && session) {
      const creds = extractSessionCredentials(session);
      cookie = creds?.sessionCookie;
    }

    if (!cookie) {
      return res.status(400).json({
        success: false,
        error: '必须提供 sessionCookie 或包含有效 sessionCookie 的 session 对象',
      });
    }

    const cleanId = String(id || `acc_${Date.now()}`).trim();
    const cleanName = String(name || cleanId).trim();

    // 预校验官方会话有效性
    const validation = await validateOfficialSession(cookie);
    if (!validation.valid) {
      return res.status(400).json({
        success: false,
        error: `官方会话验证失败: ${validation.error}`,
      });
    }

    const created = await createAccount({
      id: cleanId,
      name: cleanName,
      sessionCookie: validation.sessionCookie || cookie,
      accessToken: validation.accessToken,
      tokenExpiresAt: validation.tokenExpiresAt,
      metadata: metadata || {},
    });

    res.status(201).json({
      success: true,
      message: '账号创建并验证成功',
      data: created,
    });
  } catch (error) {
    await recordError('server.admin.create_account', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * GET /v1/admin/accounts/:id
 * 获取指定账号详细信息
 */
router.get('/accounts/:id', async (req, res) => {
  try {
    const account = await getAccount(req.params.id);
    if (!account) {
      return res.status(404).json({ success: false, error: '账号不存在' });
    }
    res.json({ success: true, data: account });
  } catch (error) {
    await recordError('server.admin.get_account', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * PATCH /v1/admin/accounts/:id
 * 更新账号基础属性 (名称、启停状态)
 */
router.patch('/accounts/:id', async (req, res) => {
  try {
    const { name, status } = req.body;
    const updated = await updateAccountInfo(req.params.id, { name, status });
    if (!updated) {
      return res.status(404).json({ success: false, error: '账号不存在或未提供任何更新项' });
    }
    res.json({ success: true, data: updated });
  } catch (error) {
    await recordError('server.admin.update_account_info', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * POST /v1/admin/accounts/:id/session
 * 更新账号会话凭据并重新激活
 */
router.post('/accounts/:id/session', async (req, res) => {
  try {
    const { sessionCookie, session } = req.body;
    let cookie = sessionCookie;
    if (!cookie && session) {
      const creds = extractSessionCredentials(session);
      cookie = creds?.sessionCookie;
    }

    if (!cookie) {
      return res.status(400).json({
        success: false,
        error: '必须提供 sessionCookie 或包含有效 sessionCookie 的 session 对象',
      });
    }

    const validation = await validateOfficialSession(cookie);
    if (!validation.valid) {
      return res.status(400).json({
        success: false,
        error: `官方会话验证失败: ${validation.error}`,
      });
    }

    const updated = await updateAccountCredentials(req.params.id, {
      sessionCookie: validation.sessionCookie || cookie,
      accessToken: validation.accessToken,
      tokenExpiresAt: validation.tokenExpiresAt,
      status: 'active',
    });

    if (!updated) {
      return res.status(404).json({ success: false, error: '账号不存在' });
    }

    res.json({
      success: true,
      message: '会话凭据已更新并验证成功',
      data: updated,
    });
  } catch (error) {
    await recordError('server.admin.update_account_session', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * POST /v1/admin/accounts/:id/sync
 * 立即触发指定账号消息同步
 */
router.post('/accounts/:id/sync', async (req, res) => {
  try {
    const account = await getAccount(req.params.id);
    if (!account) {
      return res.status(404).json({ success: false, error: '账号不存在' });
    }

    await touchAccountSignal('sync', req.params.id);
    res.json({ success: true, message: '同步任务已调度' });
  } catch (error) {
    await recordError('server.admin.sync_account', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * DELETE /v1/admin/accounts/:id
 * 删除账号
 */
router.delete('/accounts/:id', async (req, res) => {
  try {
    const deleted = await deleteAccount(req.params.id);
    if (!deleted) {
      return res.status(404).json({ success: false, error: '账号不存在' });
    }
    res.json({ success: true, message: '账号已删除' });
  } catch (error) {
    await recordError('server.admin.delete_account', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * GET /v1/admin/messages
 * 管理后台专属消息查询端点 (支持全量与账号筛选)
 */
router.get('/messages', async (req, res) => {
  try {
    const limit = Math.min(Math.max(Number.parseInt(req.query.limit || '50', 10), 1), 200);
    const offset = Math.max(Number.parseInt(req.query.offset || '0', 10), 0);
    const memberId = req.query.memberId ? String(req.query.memberId).trim() : null;
    const type = req.query.type ? String(req.query.type).trim() : null;
    const accountId = req.query.accountId ? String(req.query.accountId).trim() : null;
    const query = req.query.q ? String(req.query.q).trim() : null;

    const conditions = [];
    const params = [];

    if (memberId) {
      params.push(memberId);
      conditions.push(`member_id = $${params.length}`);
    }
    if (type) {
      params.push(type);
      conditions.push(`type = $${params.length}`);
    }
    if (accountId) {
      params.push(accountId);
      conditions.push(`source_accounts ? $${params.length}`);
    }
    if (query) {
      params.push(`%${query}%`);
      conditions.push(`(text ILIKE $${params.length} OR member_name ILIKE $${params.length})`);
    }

    const whereClause = conditions.length > 0 ? `WHERE ${conditions.join(' AND ')}` : '';

    const countSql = `SELECT COUNT(*)::int AS count FROM messages ${whereClause}`;
    const totalCount = (await queryOne(countSql, params))?.count || 0;

    params.push(limit);
    const limitIdx = params.length;
    params.push(offset);
    const offsetIdx = params.length;

    const selectSql = `
      SELECT id, member_id, member_name, member_avatar_url, phone_image_url,
             type, text, media_url, thumbnail_url, duration_seconds, sent_at,
             media_local_path, thumbnail_local_path, source_accounts, created_at
      FROM messages
      ${whereClause}
      ORDER BY sent_at DESC
      LIMIT $${limitIdx} OFFSET $${offsetIdx}
    `;

    const messages = await queryAll(selectSql, params);

    res.json({
      success: true,
      data: {
        total: totalCount,
        limit,
        offset,
        messages,
      },
    });
  } catch (error) {
    await recordError('server.admin.messages', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

export default router;
