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

export default router;
