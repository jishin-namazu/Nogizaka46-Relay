import test from 'node:test';
import assert from 'node:assert/strict';
import express from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { authenticate } from '../src/middleware/auth.js';
import adminRouter from '../src/routes/admin.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const adminPublicDir = path.resolve(__dirname, '../public/admin');

test('admin dashboard static files and router contract', async (t) => {
  process.env.API_KEY = 'test_secret_key_123';

  const app = express();
  app.use(express.json());

  // 挂载 /admin 静态资源
  app.use('/admin', express.static(adminPublicDir));
  app.get(['/admin', '/admin/*'], (req, res, next) => {
    if (req.path.startsWith('/admin') && !req.path.includes('.')) {
      return res.sendFile(path.join(adminPublicDir, 'index.html'));
    }
    next();
  });

  // 挂载 /v1/admin 路由
  app.use('/v1/admin', authenticate, adminRouter);

  const server = await new Promise(resolve => {
    const s = app.listen(0, () => resolve(s));
  });
  const port = server.address().port;
  const baseUrl = `http://localhost:${port}`;

  t.after(() => {
    server.close();
  });

  // 1. 测试 /admin 静态页面访问
  const adminRes = await fetch(`${baseUrl}/admin`);
  assert.equal(adminRes.status, 200);
  const adminHtml = await adminRes.text();
  assert.ok(adminHtml.includes('Nogi Relay'));
  assert.ok(adminHtml.includes('多账号管理'));

  // 2. 测试未携带 API 密钥访问 /v1/admin/overview 应返回 401
  const unauthRes = await fetch(`${baseUrl}/v1/admin/overview`);
  assert.equal(unauthRes.status, 401);

  // 3. 测试错误 API 密钥访问应返回 401
  const wrongKeyRes = await fetch(`${baseUrl}/v1/admin/overview`, {
    headers: { Authorization: 'Bearer wrong_key' },
  });
  assert.equal(wrongKeyRes.status, 401);

  // 4. 测试携带正确 Bearer Token 访问 /v1/admin/browser-session/status
  const authRes = await fetch(`${baseUrl}/v1/admin/browser-session/status`, {
    headers: { Authorization: 'Bearer test_secret_key_123' },
  });
  assert.equal(authRes.status, 200);
  const statusJson = await authRes.json();
  assert.equal(statusJson.success, true);
});
