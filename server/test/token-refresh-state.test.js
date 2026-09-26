import test from 'node:test';
import assert from 'node:assert/strict';
import { NogiBrowserMonitor } from '../src/monitor/nogi-browser.js';

function makeToken(expiresInSeconds) {
  const exp = Math.floor(Date.now() / 1000) + expiresInSeconds;
  const payload = Buffer.from(JSON.stringify({ exp })).toString('base64url');
  return 'header.' + payload + '.signature';
}

function mockResponse({ ok, status, body, text }) {
  return {
    ok,
    status,
    headers: { get: () => null },
    json: async () => body,
    text: async () => text,
  };
}

test('refreshAccessToken reports renewing then active for the audit console', async () => {
  const calls = [];
  const monitor = new NogiBrowserMonitor({
    accountId: 'acc_renew_ok',
    accountName: 'Renew OK',
    sessionCookie: 'sess_renew_ok',
    onStateChange: async updates => { calls.push(updates); },
  });

  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => mockResponse({
    ok: true,
    status: 200,
    body: { access_token: makeToken(3600), expires_in: 3600 },
  });
  try {
    await monitor.refreshAccessToken();
  } finally {
    globalThis.fetch = originalFetch;
  }

  assert.equal(calls[0].tokenRefreshState, 'renewing');
  assert.equal(calls[0].tokenRefreshError, null);
  const last = calls.at(-1);
  assert.equal(last.status, 'active');
  assert.equal(last.tokenRefreshState, 'active');
  assert.ok(last.tokenRefreshedAt instanceof Date);
  assert.ok(last.tokenExpiresAt instanceof Date);
  assert.equal(last.tokenRefreshError, null);
});

test('refreshAccessToken reports failed state when the official API errors', async () => {
  const calls = [];
  const monitor = new NogiBrowserMonitor({
    accountId: 'acc_renew_fail',
    accountName: 'Renew Fail',
    sessionCookie: 'sess_renew_fail',
    onStateChange: async updates => { calls.push(updates); },
  });

  const originalFetch = globalThis.fetch;
  const originalError = console.error;
  globalThis.fetch = async () => mockResponse({ ok: false, status: 500, text: 'boom' });
  console.error = () => {};
  try {
    await assert.rejects(() => monitor.refreshAccessToken(), /500/);
  } finally {
    console.error = originalError;
    globalThis.fetch = originalFetch;
  }

  assert.equal(calls[0].tokenRefreshState, 'renewing');
  const last = calls.at(-1);
  assert.equal(last.tokenRefreshState, 'failed');
  assert.match(last.tokenRefreshError, /500/);
});

test('pauseAuthentication marks the token refresh as failed', async () => {
  const calls = [];
  const monitor = new NogiBrowserMonitor({
    accountId: 'acc_renew_pause',
    accountName: 'Renew Pause',
    sessionCookie: 'sess_renew_pause',
    onStateChange: async updates => { calls.push(updates); },
  });
  // 预置 paused 状态，避免测试向真实审计日志写入 auth_paused 记录
  monitor.authPaused = true;
  await monitor.pauseAuthentication(new Error('token refresh exhausted'));

  const last = calls.at(-1);
  assert.equal(last.status, 'error');
  assert.equal(last.tokenRefreshState, 'failed');
  assert.match(last.tokenRefreshError, /exhausted/);
});

test('forceRenewToken refreshes and resumes a paused monitor', async () => {
  const calls = [];
  const monitor = new NogiBrowserMonitor({
    accountId: 'acc_force_ok',
    accountName: 'Force OK',
    sessionCookie: 'sess_force_ok',
    onStateChange: async updates => { calls.push(updates); },
  });
  monitor.authPaused = true;
  monitor.accessToken = '';

  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => mockResponse({
    ok: true,
    status: 200,
    body: { access_token: makeToken(3600), expires_in: 3600 },
  });
  try {
    const result = await monitor.forceRenewToken();
    assert.equal(result.ok, true);
  } finally {
    globalThis.fetch = originalFetch;
  }

  assert.equal(monitor.authPaused, false);
  assert.equal(calls[0].tokenRefreshState, 'renewing');
  assert.equal(calls.at(-1).tokenRefreshState, 'active');
});

test('forceRenewToken reports failure without throwing', async () => {
  const monitor = new NogiBrowserMonitor({
    accountId: 'acc_force_fail',
    accountName: 'Force Fail',
    sessionCookie: 'sess_force_fail',
  });

  const originalFetch = globalThis.fetch;
  const originalError = console.error;
  globalThis.fetch = async () => mockResponse({ ok: false, status: 503, text: 'down' });
  console.error = () => {};
  let result;
  try {
    result = await monitor.forceRenewToken();
  } finally {
    console.error = originalError;
    globalThis.fetch = originalFetch;
  }

  assert.equal(result.ok, false);
  assert.match(result.error, /503/);
});
