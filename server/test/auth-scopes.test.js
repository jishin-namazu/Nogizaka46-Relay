import test from 'node:test';
import assert from 'node:assert/strict';
import express from 'express';
import http from 'node:http';
import { authenticate, configuredTokens, isAdminToken, isClientToken } from '../src/middleware/auth.js';
import devicesRouter from '../src/routes/devices.js';
import messagesRouter from '../src/routes/messages.js';
import { NogiMediaServer } from '../src/monitor/media-server.js';

test('client token registers devices and reads messages without administrator access', async t => {
  const previous = {
    ADMIN_TOKEN: process.env.ADMIN_TOKEN,
    CLIENT_TOKEN: process.env.CLIENT_TOKEN,
  };
  process.env.ADMIN_TOKEN = 'admin-test-secret';
  process.env.CLIENT_TOKEN = 'client-test-secret';
  t.after(() => {
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  });

  const app = express();
  app.use(express.json());
  app.use('/v1/devices', devicesRouter);
  app.use('/v1/messages', messagesRouter);
  app.get('/v1/admin/probe', authenticate, (_req, res) => res.json({ ok: true }));
  const server = await new Promise(resolve => {
    const listener = app.listen(0, () => resolve(listener));
  });
  t.after(() => server.close());
  const base = `http://127.0.0.1:${server.address().port}`;
  const call = (path, token, method = 'GET') => fetch(`${base}${path}`, {
    method,
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });

  assert.equal((await call('/v1/devices', 'client-test-secret', 'POST')).status, 400);
  assert.equal((await call('/v1/devices', 'client-test-secret')).status, 401);
  assert.equal((await call('/v1/devices/1', 'client-test-secret', 'DELETE')).status, 401);
  assert.equal((await call('/v1/devices/1', 'client-test-secret', 'PATCH')).status, 401);
  assert.equal((await call('/v1/messages/x/media/invalid', 'client-test-secret')).status, 400);
  assert.equal((await call('/v1/messages/x/media/invalid', 'admin-test-secret')).status, 400);
  assert.equal((await call('/v1/messages/x/media/invalid', 'legacy-test-secret')).status, 401);
  assert.equal((await call('/v1/messages/x/media/invalid', null)).status, 401);
  assert.equal((await call('/v1/messages/x/played', 'client-test-secret', 'PATCH')).status, 401);
  assert.equal((await call('/v1/admin/probe', 'client-test-secret')).status, 401);
  assert.equal((await call('/v1/admin/probe', 'admin-test-secret')).status, 200);
  assert.equal((await call('/v1/devices', 'admin-test-secret', 'POST')).status, 400);
  assert.equal((await call('/v1/devices', null, 'POST')).status, 401);
  assert.equal(isAdminToken('admin-test-secret'), true);
  assert.equal(isAdminToken('client-test-secret'), false);
  assert.equal(isAdminToken('legacy-test-secret'), false);
  assert.equal(isClientToken('client-test-secret'), true);
  assert.equal(isClientToken('legacy-test-secret'), false);

  const media = new NogiMediaServer();
  const mediaListener = http.createServer((request, response) => {
    void media.handle(request, response);
  });
  await new Promise(resolve => mediaListener.listen(0, resolve));
  t.after(() => mediaListener.close());
  const mediaUrl = `http://127.0.0.1:${mediaListener.address().port}/v1/messages/x/media/invalid`;
  assert.equal((await fetch(mediaUrl)).status, 401);
  assert.equal((await fetch(mediaUrl, { headers: { Authorization: 'Bearer client-test-secret' } })).status, 404);
  assert.equal((await fetch(mediaUrl, { headers: { Authorization: 'Bearer admin-test-secret' } })).status, 404);

  process.env.CLIENT_TOKEN = 'admin-test-secret';
  assert.throws(() => configuredTokens(), /must differ/);
  process.env.CLIENT_TOKEN = 'client-test-secret';

  delete process.env.ADMIN_TOKEN;
  assert.equal((await call('/v1/admin/probe', 'legacy-test-secret')).status, 401);
  assert.equal((await call('/v1/admin/probe', 'admin-test-secret')).status, 401);
});
