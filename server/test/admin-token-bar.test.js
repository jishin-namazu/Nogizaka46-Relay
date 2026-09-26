import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const adminHtmlPath = path.resolve(__dirname, '../public/admin/index.html');

function createElement() {
  return {
    innerHTML: '',
    innerText: '',
    textContent: '',
    className: '',
    value: '',
    disabled: false,
    hidden: false,
    options: [],
    style: {},
    classList: { add() {}, remove() {}, contains() { return false; }, toggle() {} },
    addEventListener() {},
    appendChild() {},
    querySelector() { return null; },
    querySelectorAll() { return []; },
    getAttribute() { return null; },
    setAttribute() {},
    removeAttribute() {},
  };
}

// 在受控的 vm 沙箱中执行管理端内联脚本，以便直接验证动画逻辑。
function loadAdminScript() {
  const html = fs.readFileSync(adminHtmlPath, 'utf8');
  const open = html.lastIndexOf('<script>');
  const close = html.indexOf('</script>', open);
  assert.ok(open !== -1 && close > open, 'admin inline script block should exist');
  const code = html.slice(open + '<script>'.length, close);

  const elements = new Map();
  const state = { confirmResult: false, alerts: [] };
  const context = {
    window: { location: { origin: 'http://localhost' } },
    URLSearchParams,
    FormData,
    localStorage: { getItem() { return null; }, setItem() {}, removeItem() {} },
    document: {
      hidden: false,
      body: { style: {} },
      getElementById(id) {
        if (!elements.has(id)) elements.set(id, createElement());
        return elements.get(id);
      },
      querySelectorAll() { return []; },
      createElement() { return createElement(); },
      addEventListener() {},
    },
    fetch: async () => ({ status: 200, json: async () => ({ success: false, error: 'stub' }) }),
    setInterval() { return 0; },
    clearInterval() {},
    setTimeout() { return 0; },
    clearTimeout() {},
    console: { log() {}, warn() {}, error() {} },
    alert(message) { state.alerts.push(String(message)); },
    confirm() { return state.confirmResult; },
  };
  vm.createContext(context);
  vm.runInContext(code, context, { filename: 'admin-inline.js' });
  // 暴露元素表与可变状态，便于测试直接检查运行时生成的 DOM 片段
  context.__elements = elements;
  context.__state = state;
  return context;
}

test('tokenBarSnapshot reports the live countdown states', () => {
  const context = loadAdminScript();
  const snapshot = context.tokenBarSnapshot;
  assert.equal(typeof snapshot, 'function', 'tokenBarSnapshot should be exposed by the inline script');

  // 多加 1.5 秒缓冲，避免 Math.floor(diffMs/1000) 落在分钟边界上导致抖动
  const later = ms => new Date(Date.now() + ms + 1500).toISOString();

  const healthy = snapshot(later(90 * 60 * 1000), 'active');
  assert.equal(healthy.timeText, '1h 30m');
  assert.equal(healthy.fillClass, 'bg-emerald-500');
  assert.equal(healthy.animated, true);
  assert.equal(healthy.urgent, false);

  const warning = snapshot(later(10 * 60 * 1000), 'active');
  assert.equal(warning.fillClass, 'bg-amber-500');
  assert.equal(warning.animated, true);
  assert.equal(warning.urgent, false);
  assert.match(warning.timeText, /^(09|10):\d{2}$/);

  const urgent = snapshot(later(2 * 60 * 1000), 'active');
  assert.equal(urgent.fillClass, 'bg-rose-500');
  assert.equal(urgent.urgent, true);
  assert.match(urgent.timeText, /^(01|02):\d{2}$/);

  const past = snapshot(new Date(Date.now() - 1000).toISOString(), 'active');
  assert.equal(past.timeText, '已过期');
  assert.equal(past.animated, false);

  assert.equal(snapshot(new Date(Date.now() + 60_000).toISOString(), 'expired').timeText, '已失效');
  assert.equal(snapshot('', 'active').timeText, '无信息');
});

test('tokenStateInfo exposes explicit renewal states', () => {
  const context = loadAdminScript();
  const stateInfo = context.tokenStateInfo;
  assert.equal(typeof stateInfo, 'function', 'tokenStateInfo should be exposed by the inline script');

  const later = ms => new Date(Date.now() + ms + 1500).toISOString();

  const scheduled = stateInfo(later(30 * 60 * 1000), 'active', 'active', '', '');
  assert.equal(scheduled.spinning, false);
  assert.match(scheduled.text, /^将于 [0-9]{2}:[0-9]{2} 自动续期$/);

  const inWindow = stateInfo(later(2 * 60 * 1000), 'active', 'active', '', '');
  assert.equal(inWindow.spinning, true);
  assert.match(inWindow.text, /续期窗口/);

  const renewing = stateInfo(later(30 * 60 * 1000), 'active', 'renewing', '', '');
  assert.equal(renewing.spinning, true);
  assert.equal(renewing.text, '正在续期访问令牌…');

  const succeeded = stateInfo(later(59 * 60 * 1000), 'active', 'active', new Date().toISOString(), '');
  assert.equal(succeeded.spinning, false);
  assert.equal(succeeded.text, '✓ 刚刚续期成功');

  const failed = stateInfo(later(30 * 60 * 1000), 'active', 'failed', '', 'getaddrinfo ENOTFOUND api.message.nogizaka46.com');
  assert.equal(failed.spinning, true);
  assert.match(failed.text, /正在重试/);
  assert.match(failed.text, /ENOTFOUND/);

  const expired = stateInfo(later(30 * 60 * 1000), 'expired', 'failed', '', 'HTTP 400');
  assert.equal(expired.spinning, false);
  assert.match(expired.text, /续期失败：HTTP 400/);
});

test('updateTokenBars animates the bar and its renewal state in place', () => {
  const context = loadAdminScript();
  const expiry = new Date(Date.now() + 10 * 60 * 1000).toISOString();

  const fill = { style: {}, className: '' };
  const time = { textContent: '', className: '' };
  const state = { textContent: '', className: '' };
  const spinner = { className: '' };
  const stateText = { textContent: '' };
  const node = {
    getAttribute(name) {
      return {
        'data-expires-at': expiry,
        'data-status': 'active',
        'data-refresh-state': 'renewing',
        'data-refreshed-at': '',
        'data-refresh-error': '',
      }[name] ?? null;
    },
    querySelector(selector) {
      if (selector === '[data-token-time]') return time;
      if (selector === '[data-token-fill]') return fill;
      if (selector === '[data-token-state]') return state;
      if (selector === '[data-token-spinner]') return spinner;
      if (selector === '[data-token-state-text]') return stateText;
      return null;
    },
  };

  context.document.querySelectorAll = selector => (selector === '[data-token-bar]' ? [node] : []);
  context.updateTokenBars();

  assert.equal(time.textContent, context.tokenBarSnapshot(expiry, 'active').timeText);
  const width = Number.parseFloat(fill.style.width);
  assert.ok(Number.isFinite(width) && width > 0 && width < 100, 'bar width should shrink below 100%');
  assert.match(fill.className, /token-bar-fill/);
  assert.match(fill.className, /bg-amber-500/);

  assert.equal(stateText.textContent, '正在续期访问令牌…');
  assert.match(state.className, /text-amber-600/);
  assert.ok(!spinner.className.includes('hidden'), 'spinner should be visible while renewing');
});

test('renderMsgPagination uses brand colors and a compact mobile layout', () => {
  const context = loadAdminScript();
  context.renderMsgPagination(100, 30, 0);

  const el = context.__elements.get('msgPaginationButtons');
  assert.ok(el, 'pagination buttons container should be resolved');
  const html = el.innerHTML;

  assert.match(html, /nogi-page-btn/);
  assert.match(html, /nogi-brand/, 'active page should use the page brand color');
  assert.doesNotMatch(html, /bg-purple-600/, 'old mismatched purple should be gone');
  assert.match(html, /hidden sm:inline-flex/, '首页/末页/数字页 should be desktop-only');
  assert.match(html, /sm:hidden/, 'mobile page indicator should exist');
  assert.match(html, /第 1 \/ 4 页/, 'mobile indicator should show current/total pages');
  assert.match(html, /min-h-9/, 'buttons should have a comfortable tap target');
  assert.match(html, /msgPageJumpInput/, 'should offer a page-jump input');
  assert.match(html, /jumpToPage\('msg'/, 'jump control should call the generic jumpToPage');
  assert.match(html, /inputmode="numeric"/, 'jump input should request a numeric keyboard');
});

test('jumpToMsgPage clamps out-of-range input and reloads on change', () => {
  const context = loadAdminScript();
  context.renderMsgPagination(100, 30, 0); // totalPages = 4

  const input = context.document.getElementById('msgPageJumpInput');
  assert.ok(input, 'jump input should be resolved');

  let reloads = 0;
  context.loadMessages = () => { reloads += 1; };

  input.value = '3';
  context.jumpToMsgPage(4);
  assert.equal(reloads, 1, 'valid in-range page should reload');
  assert.equal(input.value, '3');

  input.value = '999';
  context.jumpToMsgPage(4);
  assert.equal(reloads, 2, 'out-of-range page should clamp and reload');
  assert.equal(input.value, '4');

  input.value = 'nope';
  context.jumpToMsgPage(4);
  assert.equal(reloads, 2, 'invalid input should not reload');
  assert.equal(input.value, '4');
});

test('voice-call background image is clickable and previewable', async () => {
  const context = loadAdminScript();
  context.fetch = async () => ({
    status: 200,
    json: async () => ({
      success: true,
      data: {
        total: 1,
        limit: 30,
        offset: 0,
        messages: [{
          id: 'm_audio_1',
          member_name: '池田 瑛紗',
          type: 'audio',
          sent_at: new Date().toISOString(),
          phone_image_local_path: '/data/phone.jpg',
          duration_seconds: 12,
          source_accounts: ['acc_1'],
        }],
      },
    }),
  });

  await context.loadMessages();
  const html = context.__elements.get('messagesContainer').innerHTML;

  assert.match(html, /phone_image/, 'should reference the phone image');
  assert.match(
    html,
    /<a href="[^"]*phone_image[^"]*" target="_blank" rel="noopener noreferrer"/,
    'phone image should be wrapped in a new-tab link',
  );
  assert.match(html, /data-preview-src="[^"]*phone_image[^"]*"/, 'phone image should expose the lightbox hook');
  assert.match(html, /cursor-zoom-in/, 'should show a clickability cue');

  context.openImageLightbox('http://localhost/v1/messages/m_audio_1/media/phone_image');
  const lightboxImg = context.__elements.get('imageLightboxImg');
  assert.ok(lightboxImg, 'lightbox image element should be resolved');
  assert.match(String(lightboxImg.src), /phone_image/);

  context.closeImageLightbox();
  assert.equal(lightboxImg.src, '');
});

test('message type labels use the short categories', async () => {
  const context = loadAdminScript();
  const base = { member_name: '池田 瑛紗', sent_at: new Date().toISOString(), source_accounts: [] };
  context.fetch = async () => ({
    status: 200,
    json: async () => ({
      success: true,
      data: {
        total: 4,
        limit: 30,
        offset: 0,
        messages: [
          { ...base, id: 't1', type: 'text', text: 'hi' },
          { ...base, id: 't2', type: 'audio' },
          { ...base, id: 't3', type: 'image' },
          { ...base, id: 't4', type: 'video' },
        ],
      },
    }),
  });

  await context.loadMessages();
  const html = context.__elements.get('messagesContainer').innerHTML;

  for (const label of ['文字', '语音', '图片', '视频']) {
    assert.ok(html.includes('>' + label + '</span>'), 'should render badge ' + label);
  }
  for (const old of ['文字私信', '语音来电', '写真图片', '视频动态']) {
    assert.ok(!html.includes(old), 'old label should be gone: ' + old);
  }
});

test('revokePush targets the message id and device of the last test push', async () => {
  const context = loadAdminScript();
  context.__state.confirmResult = true;

  const calls = [];
  context.fetch = async (url, opts) => {
    calls.push({ url: String(url), body: opts && opts.body });
    const payload = String(url).includes('/v1/push/revoke')
      ? { success: true, revoked_message_id: 'test-message-1', device_count: 1, result: { successCount: 1, failureCount: 0 } }
      : { success: true, message: { id: 'test-message-1' }, result: { successCount: 1 } };
    return { status: 200, json: async () => payload };
  };

  context.document.getElementById('debugPushTargetDevice').value = '12';
  await context.sendTestPush('message');
  await context.revokePush();

  const revokeCall = calls.find(c => c.url.includes('/v1/push/revoke'));
  assert.ok(revokeCall, 'revoke should be requested');
  const body = JSON.parse(revokeCall.body);
  assert.equal(body.message_id, 'test-message-1', 'revoke must carry the test push message id');
  assert.equal(body.device_id, '12', 'revoke must carry the target device');
});

test('renderPagination drives the push-log pager with jump support', () => {
  const context = loadAdminScript();
  context.renderPagination('push', 100, 30, 0);

  const el = context.__elements.get('pushPaginationButtons');
  assert.ok(el, 'push pagination container should be resolved');
  const html = el.innerHTML;

  assert.match(html, /pushPageJumpInput/, 'push pager should expose its own jump input');
  assert.match(html, /setPage\('push'/, 'push pager buttons should call setPage');
  assert.match(html, /jumpToPage\('push'/, 'push pager should call the generic jumpToPage');
  assert.match(html, /nogi-brand/, 'active page should use the brand color');
  assert.match(html, /第 1 \/ 4 页/, 'mobile indicator should show current/total');
});

test('revokePushLog posts the row message id and device', async () => {
  const context = loadAdminScript();
  context.__state.confirmResult = true;

  const calls = [];
  context.fetch = async (url, opts) => {
    calls.push({ url: String(url), body: opts && opts.body });
    const u = String(url);
    const now = new Date().toISOString();
    const payload = u.includes('/v1/admin/push-logs')
      ? {
        success: true,
        data: {
          total: 1,
          limit: 30,
          offset: 0,
          logs: [{ id: 7, message_id: 'm1', device_id: 12, status: 'success', member_name: '池田 瑛紗', type: 'text', text: 'hi', created_at: now, sent_at: now, device_label: 'OPPO PKU110' }],
        },
      }
      : { success: true, revoked_message_id: 'm1', result: { successCount: 1, failureCount: 0 } };
    return { status: 200, json: async () => payload };
  };

  await context.loadPushLogs();
  await context.revokePushLog(7);

  const listCall = calls.find(c => c.url.includes('/v1/admin/push-logs'));
  assert.ok(listCall, 'push logs should be requested');
  const revokeCall = calls.find(c => c.url.includes('/v1/push/revoke'));
  assert.ok(revokeCall, 'revoke should be requested');
  const body = JSON.parse(revokeCall.body);
  assert.equal(body.message_id, 'm1');
  assert.equal(body.device_id, 12);
});

