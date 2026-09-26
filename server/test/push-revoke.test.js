import test from 'node:test';
import assert from 'node:assert/strict';
import {
  resolveRevokeMessageId,
  rememberTransientPush,
  getLastTransientPush,
  clearTransientPush,
  TRANSIENT_PUSH_TTL_MS,
} from '../src/services/push-revoke.js';

const NOW = 1_800_000_000_000;

test('explicit message_id always wins', () => {
  assert.equal(resolveRevokeMessageId({
    explicitMessageId: 'explicit-1',
    transient: { messageId: 'transient', at: NOW },
    lastLog: { message_id: 'log', created_at: new Date(NOW) },
    lastMessage: { id: 'message' },
    now: NOW,
  }), 'explicit-1');
});

test('fresh transient test push beats the last logged real push', () => {
  // 线上复现的 bug：测试推送不落 push_logs，必须优先撤回它
  const id = resolveRevokeMessageId({
    transient: { messageId: 'test-message-1', at: NOW - 1000 },
    lastLog: { message_id: '174782', created_at: new Date(NOW - 60_000) },
    lastMessage: { id: '174799' },
    now: NOW,
  });
  assert.equal(id, 'test-message-1');
});

test('newer logged real push wins over an older transient push', () => {
  const id = resolveRevokeMessageId({
    transient: { messageId: 'test-message-1', at: NOW - 120_000 },
    lastLog: { message_id: '174900', created_at: new Date(NOW - 5_000) },
    lastMessage: { id: '174799' },
    now: NOW,
  });
  assert.equal(id, '174900');
});

test('falls back to the latest message when there is no push log', () => {
  assert.equal(resolveRevokeMessageId({ lastMessage: { id: '174799' }, now: NOW }), '174799');
});

test('stale transient push is ignored', () => {
  const id = resolveRevokeMessageId({
    transient: { messageId: 'test-message-1', at: NOW - TRANSIENT_PUSH_TTL_MS - 1 },
    lastLog: { message_id: '174782', created_at: new Date(NOW - TRANSIENT_PUSH_TTL_MS - 60_000) },
    now: NOW,
  });
  assert.equal(id, '174782');
});

test('returns null when nothing is known', () => {
  assert.equal(resolveRevokeMessageId({ now: NOW }), null);
});

test('rememberTransientPush stores the message id and normalises the device id', () => {
  clearTransientPush();
  rememberTransientPush('test-message-2', '12', NOW);
  const stored = getLastTransientPush();
  assert.equal(stored.messageId, 'test-message-2');
  assert.equal(stored.deviceId, 12);
  assert.equal(stored.at, NOW);
  clearTransientPush();
  assert.equal(getLastTransientPush(), null);
});
