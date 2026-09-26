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
  process.env.ADMIN_TOKEN = 'test_secret_key_123';

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
  // 令牌有效期进度条应暴露可被秒级 ticker 原地更新的锚点
  assert.ok(adminHtml.includes('data-token-bar'));
  assert.ok(adminHtml.includes('data-token-state'));
  assert.ok(adminHtml.includes('updateTokenBars'));
  // 主动续期按钮与移动端操作区
  assert.ok(adminHtml.includes('renewAccount('));
  assert.ok(adminHtml.includes('nogi-card-actions'));
  // 消息翻页按钮：品牌配色 + 移动端布局
  assert.ok(adminHtml.includes('nogi-pagination-buttons'));
  assert.ok(adminHtml.includes('nogi-page-btn'));
  assert.ok(adminHtml.includes('msgPageJumpInput'));
  assert.ok(adminHtml.includes('jumpToMsgPage'));
  // 图片预览灯箱（语音来电背景图可点开）
  assert.ok(adminHtml.includes('imageLightbox'));
  assert.ok(adminHtml.includes('data-preview-src'));
  assert.ok(adminHtml.includes('openImageLightbox'));
  // 消息分类使用简短名称：文字 / 语音 / 图片 / 视频
  assert.ok(adminHtml.includes('<option value="text">文字</option>'));
  assert.ok(adminHtml.includes('<option value="audio">语音</option>'));
  assert.ok(adminHtml.includes('<option value="image">图片</option>'));
  assert.ok(adminHtml.includes('<option value="video">视频</option>'));
  assert.ok(!adminHtml.includes('文字私信'));
  // 推送管理页（查看 / 撤回 / 分页）
  assert.ok(adminHtml.includes('pane-pushlogs'));
  assert.ok(adminHtml.includes('revokePushLog'));
  assert.ok(adminHtml.includes('renderPagination'));
  assert.ok(adminHtml.includes('pushPageJumpInput'));

  // 2. 测试未携带 API 密钥访问 /v1/admin/overview 应返回 401
  const unauthRes = await fetch(`${baseUrl}/v1/admin/overview`);
  assert.equal(unauthRes.status, 401);

  // 2b. 主动续期端点应同样受鉴权保护
  const renewUnauthRes = await fetch(`${baseUrl}/v1/admin/accounts/acc_demo/renew`, { method: 'POST' });
  assert.equal(renewUnauthRes.status, 401);

  // 2c. 推送记录端点同样受鉴权保护
  const pushLogsUnauthRes = await fetch(`${baseUrl}/v1/admin/push-logs`);
  assert.equal(pushLogsUnauthRes.status, 401);

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

  // 5. 测试通过 query parameter (?token=...) 鉴权
  const queryAuthRes = await fetch(`${baseUrl}/v1/admin/browser-session/status?token=test_secret_key_123`);
  assert.equal(queryAuthRes.status, 200);
  const queryStatusJson = await queryAuthRes.json();
  assert.equal(queryStatusJson.success, true);
});
