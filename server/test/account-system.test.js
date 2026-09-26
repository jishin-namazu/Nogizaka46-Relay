import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {
  tokenExpiry,
  extractSessionCredentials,
  touchAccountSignal,
  getSignalFilePath,
} from '../src/services/account-service.js';
import { NogiBrowserMonitor } from '../src/monitor/nogi-browser.js';

test('tokenExpiry should parse valid JWT exp claim', () => {
  const expSeconds = Math.floor(Date.now() / 1000) + 3600;
  const payload = Buffer.from(JSON.stringify({ exp: expSeconds })).toString('base64url');
  const token = `header.${payload}.signature`;

  const expiry = tokenExpiry(token);
  assert.ok(expiry instanceof Date);
  assert.equal(Math.floor(expiry.getTime() / 1000), expSeconds);
});

test('tokenExpiry should return null on invalid token format', () => {
  assert.equal(tokenExpiry('invalid-token'), null);
  assert.equal(tokenExpiry(''), null);
  assert.equal(tokenExpiry(null), null);
});

test('extractSessionCredentials should extract sessionCookie from legacy Playwright storageState', () => {
  const legacyStorageState = {
    cookies: [
      { name: 'other_cookie', value: '123' },
      { name: 'session', value: 'sess_test_12345678' },
    ],
    origins: [],
  };

  const creds = extractSessionCredentials(legacyStorageState);
  assert.ok(creds);
  assert.equal(creds.sessionCookie, 'sess_test_12345678');
});

test('extractSessionCredentials should extract credentials from clean session object', () => {
  const cleanSession = {
    sessionCookie: 'sess_clean_98765432',
    accessToken: 'test_token',
  };

  const creds = extractSessionCredentials(cleanSession);
  assert.ok(creds);
  assert.equal(creds.sessionCookie, 'sess_clean_98765432');
  assert.equal(creds.accessToken, 'test_token');
});

test('touchAccountSignal should write signal file with timestamp and action', async () => {
  await touchAccountSignal('test_action', 'acc_test_123');
  const signalPath = getSignalFilePath();
  const content = await fs.readFile(signalPath, 'utf8');
  const data = JSON.parse(content);

  assert.equal(data.action, 'test_action');
  assert.equal(data.accountId, 'acc_test_123');
  assert.ok(data.timestamp > 0);
});

test('NogiBrowserMonitor should normalize raw message correctly', () => {
  const monitor = new NogiBrowserMonitor({
    accountId: 'acc_unit_test',
    accountName: 'Unit Test Account',
  });

  const rawMessage = {
    id: '10001',
    content_type: 'picture',
    published_at: '2026-09-20T00:00:00Z',
    file: 'https://cdn.example.com/photo.jpg',
    member_name: '池田 瑛紗',
    member_id: '4601',
  };

  const group = { id: 4601, name: '池田 瑛紗' };
  const normalized = monitor.normalizeMessage(rawMessage, group);

  assert.ok(normalized);
  assert.equal(normalized.id, '10001');
  assert.equal(normalized.type, 'image');
  assert.equal(normalized.member_name, '池田 瑛紗');
  assert.equal(normalized.media_url, 'https://cdn.example.com/photo.jpg');
});

test('NogiBrowserMonitor should attach source_account_id when processing messages', async () => {
  let savedMessage = null;
  const mockMessageStore = {
    saveMessage: async (msg) => {
      savedMessage = msg;
      return { isNew: true, message: msg };
    },
  };

  const mockPusher = {
    pushMessage: async () => {},
  };

  const monitor = new NogiBrowserMonitor({
    accountId: 'acc_multi_source',
    accountName: 'Multi Source Account',
    messageStore: mockMessageStore,
    pusher: mockPusher,
  });

  const message = {
    id: 'msg_999',
    member_id: '4602',
    member_name: '井上 和',
    type: 'text',
    text: 'こんにちは！',
    sent_at: '2026-09-20T00:00:00Z',
  };

  const result = await monitor.processMessage(message, false);
  assert.equal(result.isNew, true);
  assert.ok(savedMessage);
  assert.equal(savedMessage.source_account_id, 'acc_multi_source');
});
