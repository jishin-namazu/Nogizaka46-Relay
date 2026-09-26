import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const html = fs.readFileSync(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../public/admin/index.html'), 'utf8');
const script = html.slice(html.lastIndexOf('<script>') + 8, html.lastIndexOf('</script>'));

function loadPage(initialToken = '') {
  const elements = new Map();
  const storage = new Map(initialToken ? [['nogi_admin_api_key', initialToken]] : []);
  const requests = [];
  function element(id) {
    if (!elements.has(id)) {
      const classes = new Set(id === 'apiKeyModal' ? ['hidden'] : []);
      elements.set(id, {
        hidden: id === 'adminContent',
        value: '',
        textContent: '',
        disabled: false,
        addEventListener() {},
        classList: {
          add(name) { classes.add(name); },
          remove(name) { classes.delete(name); },
          toggle(name, force) { if (force) classes.add(name); else classes.delete(name); },
          contains(name) { return classes.has(name); },
        },
      });
    }
    return elements.get(id);
  }
  const context = {
    window: { location: { origin: 'https://example.test' } },
    document: {
      hidden: false,
      body: { style: {} },
      getElementById: element,
      addEventListener() {},
      querySelectorAll() { return []; },
    },
    localStorage: {
      getItem(key) { return storage.get(key) || null; },
      setItem(key, value) { storage.set(key, value); },
      removeItem(key) { storage.delete(key); },
    },
    fetch: async (url, options) => {
      requests.push(url);
      const valid = options.headers.Authorization === 'Bearer admin-token';
      return { status: valid ? 200 : 401, ok: valid };
    },
    setInterval() {},
    setTimeout() {},
    URLSearchParams,
    FormData,
    console,
  };
  vm.createContext(context);
  vm.runInContext(script, context);
  context.loadAccounts = () => {};
  context.loadOverview = () => {};
  return { context, element, storage, requests };
}

test('管理页面在管理员令牌验证通过前保持隐藏', async () => {
  assert.match(html, /id="adminContent" hidden/);
  const page = loadPage();
  assert.equal(page.element('adminContent').hidden, true);
  assert.equal(page.element('apiKeyModal').classList.contains('hidden'), false);
  assert.equal(page.requests.length, 0);

  page.element('apiKeyInput').value = 'client-token';
  await page.context.saveApiKey();
  assert.equal(page.element('adminContent').hidden, true);
  assert.equal(page.storage.has('nogi_admin_api_key'), false);

  page.element('apiKeyInput').value = 'admin-token';
  await page.context.saveApiKey();
  assert.equal(page.element('adminContent').hidden, false);
  assert.equal(page.element('apiKeyModal').classList.contains('hidden'), true);
  assert.equal(page.storage.get('nogi_admin_api_key'), 'admin-token');
});

test('已保存的无效令牌不会显示管理页面', async () => {
  const page = loadPage('client-token');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(page.element('adminContent').hidden, true);
  assert.equal(page.element('apiKeyModal').classList.contains('hidden'), false);
  assert.equal(page.storage.has('nogi_admin_api_key'), false);
});

test('已保存的管理员令牌验证通过后显示管理页面', async () => {
  const page = loadPage('admin-token');
  assert.equal(page.element('adminContent').hidden, true);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(page.element('adminContent').hidden, false);
  assert.equal(page.element('apiKeyModal').classList.contains('hidden'), true);
});
