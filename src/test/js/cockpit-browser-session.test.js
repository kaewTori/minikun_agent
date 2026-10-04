const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('src/main/resources/static/cockpit/cockpit.js', 'utf8');
const start = source.indexOf('  async function browserSessionAction(action)');
const end = source.indexOf('\n  ["open", "read", "close"].forEach', start);

test('browser session validates URLs and restores controls after blocked and successful reads', async () => {
  const nodes = new Map();
  const $ = selector => {
    if (!nodes.has(selector)) nodes.set(selector, {
      value: '', textContent: '', disabled: false,
      focus() { this.focused = true; },
      setAttribute(key, value) { this[key] = value; },
      removeAttribute(key) { delete this[key]; }
    });
    return nodes.get(selector);
  };
  let calls = 0;
  let response = { failures: [{ reason: 'Cloudflare verification required' }] };
  const context = { $, URL, JSON, syncFetch: async (path, options) => {
    calls++;
    assert.equal($('#browser-session-open').disabled, true);
    assert.equal($('#browser-session-status')['aria-busy'], 'true');
    assert.equal(path, '/v1/browser/session/read');
    assert.equal(options.method, 'POST');
    assert.equal(JSON.parse(options.body).url, 'https://example.com/article');
    return response;
  } };
  vm.createContext(context);
  vm.runInContext(source.slice(start, end), context);
  $('#browser-session-url').value = 'file:///etc/passwd';
  await context.browserSessionAction('read');
  assert.equal(calls, 0);
  assert.equal($('#browser-session-url').focused, true);
  $('#browser-session-url').value = 'https://example.com/article';
  await context.browserSessionAction('read');
  assert.match($('#browser-session-status').textContent, /Cloudflare/);
  assert.equal($('#browser-session-open').disabled, false);
  response = { candidates: [{ content: 'Article' }], failures: [] };
  await context.browserSessionAction('read');
  assert.match($('#browser-session-status').textContent, /อ่านสำเร็จ/);
  context.syncFetch = async () => { throw new Error('session unavailable'); };
  await context.browserSessionAction('read');
  assert.match($('#browser-session-status').textContent, /session unavailable/);
  assert.equal($('#browser-session-read').disabled, false);
  assert.equal($('#browser-session-status')['aria-busy'], undefined);
});
