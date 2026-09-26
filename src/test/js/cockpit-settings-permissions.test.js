const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

test('device permissions live in settings and legacy links open them there', () => {
  const html = fs.readFileSync('src/main/resources/static/cockpit/index.html', 'utf8');
  const settings = html.slice(html.indexOf('<dialog id="settings-dialog"'), html.indexOf('<dialog id="visual-lightbox-dialog"'));
  assert.match(settings, /id="permission-center"/);
  assert.doesNotMatch(settings, /id="permission-center"[^>]*data-cockpit-page/);
  assert.equal((html.match(/id="permission-center"/g) || []).length, 1);

  const source = fs.readFileSync('src/main/resources/static/cockpit/cockpit.js', 'utf8');
  const calls = [];
  const context = {
    document: { querySelectorAll: () => [] },
    state: { cockpitPage: 'today' },
    switchCockpitPage: (...args) => calls.push(['page', ...args]),
    openSettings: () => calls.push(['settings']),
    updateStudioRuntimePolling: () => {},
    updateSystemPolling: () => {}
  };
  vm.createContext(context);
  vm.runInContext(source.slice(source.indexOf('  function showView('), source.indexOf('  function switchCockpitPage(')), context);
  context.showView('cockpit', 'permission-center');
  assert.deepEqual(calls, [['page', 'today', ''], ['settings']]);
});
