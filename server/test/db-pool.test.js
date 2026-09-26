import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildPoolConfig,
  isExpectedIdleClientDisconnect,
  handleIdlePoolError,
} from '../src/db/index.js';

test('buildPoolConfig defaults to no resident idle client and proactive recycling', () => {
  const config = buildPoolConfig({});
  assert.equal(config.min, 0);
  assert.equal(config.max, 20);
  assert.ok(config.idleTimeoutMillis > 0);
  assert.ok(config.maxLifetimeSeconds > 0);
  // 必须早于上游约 30 分钟（1800 秒）的空闲回收
  assert.ok(config.maxLifetimeSeconds < 1800);
  assert.equal(config.keepAlive, true);
});

test('buildPoolConfig honours overrides and keeps the zero disable switch', () => {
  const config = buildPoolConfig({
    DATABASE_URL: 'postgresql://example',
    DB_POOL_MIN: '2',
    DB_POOL_MAX: '5',
    DB_POOL_IDLE_TIMEOUT_MS: '15000',
    DB_CONNECTION_TIMEOUT_MS: '3000',
    DB_POOL_MAX_LIFETIME_SECONDS: '0',
  });
  assert.equal(config.connectionString, 'postgresql://example');
  assert.equal(config.min, 2);
  assert.equal(config.max, 5);
  assert.equal(config.idleTimeoutMillis, 15000);
  assert.equal(config.connectionTimeoutMillis, 3000);
  assert.equal(config.maxLifetimeSeconds, 0);
});

test('isExpectedIdleClientDisconnect recognises the reported idle reconnect noise', () => {
  assert.equal(
    isExpectedIdleClientDisconnect(new Error('Connection terminated unexpectedly')),
    true,
  );
  assert.equal(
    isExpectedIdleClientDisconnect(new Error('Connection terminated due to connection timeout')),
    true,
  );

  const reset = new Error('read ECONNRESET');
  reset.code = 'ECONNRESET';
  assert.equal(isExpectedIdleClientDisconnect(reset), true);

  const adminShutdown = new Error('terminating connection due to administrator command');
  adminShutdown.code = '57P01';
  assert.equal(isExpectedIdleClientDisconnect(adminShutdown), true);
});

test('isExpectedIdleClientDisconnect does not mask real database faults', () => {
  const auth = new Error('password authentication failed for user "relay"');
  auth.code = '28P01';
  assert.equal(isExpectedIdleClientDisconnect(auth), false);

  const missingTable = new Error('relation "accounts" does not exist');
  missingTable.code = '42P01';
  assert.equal(isExpectedIdleClientDisconnect(missingTable), false);

  assert.equal(isExpectedIdleClientDisconnect(null), false);
  assert.equal(isExpectedIdleClientDisconnect(undefined), false);
});

test('handleIdlePoolError suppresses expected idle disconnects from the audit log', () => {
  const recorded = [];
  const result = handleIdlePoolError(new Error('Connection terminated unexpectedly'), {
    record: (scope, error) => recorded.push({ scope, error }),
    now: Date.now(),
  });
  assert.equal(result, 'suppressed');
  assert.equal(recorded.length, 0);
});

test('handleIdlePoolError still records genuine connection faults', () => {
  const recorded = [];
  const auth = new Error('password authentication failed for user "relay"');
  auth.code = '28P01';
  const result = handleIdlePoolError(auth, {
    record: (scope, error) => recorded.push({ scope, error }),
    now: Date.now(),
  });
  assert.equal(result, 'recorded');
  assert.equal(recorded.length, 1);
  assert.equal(recorded[0].scope, 'database.idle_client');
  assert.equal(recorded[0].error, auth);
});
