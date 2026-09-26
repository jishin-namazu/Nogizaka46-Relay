/**
 * 「撤回上一次推送」的目标解析。
 *
 * 测试推送（/v1/push/test 等）不会写入 push_logs —— push_logs.message_id 有指向
 * messages 的外键，而测试消息不落库。因此不能只依赖数据库里最后一次推送记录：
 * 那样会撤回到一条无关的正式消息，用户端看到的通知不会消失。
 *
 * 这里维护一份本进程的「最近一次临时推送」记录，并与数据库中的最后一条推送日志
 * 按时间比较，取更新的一条作为撤回目标。
 */

export const TRANSIENT_PUSH_TTL_MS = 10 * 60 * 1000;

let lastTransientPush = null;

/** 记录本进程最近一次下发的临时推送（测试推送）。 */
export function rememberTransientPush(messageId, deviceId = null, at = Date.now()) {
  if (!messageId) return null;
  lastTransientPush = {
    messageId: String(messageId),
    deviceId: deviceId == null || deviceId === '' ? null : Number(deviceId),
    at,
  };
  return lastTransientPush;
}

export function getLastTransientPush() {
  return lastTransientPush;
}

export function clearTransientPush() {
  lastTransientPush = null;
}

/**
 * 解析要撤回的消息 ID。优先级：
 *   1. 调用方显式传入的 message_id
 *   2. 本进程刚下发的临时推送（TTL 内且不早于数据库最后一条推送记录）
 *   3. 数据库中最新的推送日志
 *   4. 数据库中最新的正式消息
 */
export function resolveRevokeMessageId({
  explicitMessageId = null,
  transient = null,
  lastLog = null,
  lastMessage = null,
  now = Date.now(),
} = {}) {
  const explicit = explicitMessageId == null ? '' : String(explicitMessageId).trim();
  if (explicit) return explicit;

  const transientFresh = transient
    && transient.messageId
    && Number.isFinite(transient.at)
    && now - transient.at <= TRANSIENT_PUSH_TTL_MS
    ? transient
    : null;

  const logAt = lastLog?.created_at ? new Date(lastLog.created_at).getTime() : Number.NaN;
  if (transientFresh && (!Number.isFinite(logAt) || transientFresh.at >= logAt)) {
    return String(transientFresh.messageId);
  }

  if (lastLog?.message_id) return String(lastLog.message_id);
  if (lastMessage?.id) return String(lastMessage.id);
  return null;
}

export default { resolveRevokeMessageId, rememberTransientPush, getLastTransientPush, clearTransientPush, TRANSIENT_PUSH_TTL_MS };
