const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

test('system polling stops outside the visible system page and only fetches health', async () => {
  const source = fs.readFileSync('src/main/resources/static/cockpit/cockpit.js', 'utf8');
  const start = source.indexOf('  function updateSystemPolling()');
  const end = source.indexOf('\n  function ', source.indexOf('  async function loadDashboard(', start));
  let tick;
  let hiddenView = false;
  const requests = [];
  const context = {
    state: { cockpitPage: 'system', dashboard: { timer: null, loading: new Map(), loadedAt: new Map() } },
    document: { hidden: false },
    $: () => ({ classList: { contains: () => hiddenView } }),
    setInterval: (callback, delay) => { assert.equal(delay, 5000); tick = callback; return 1; },
    clearInterval: () => { tick = null; },
    api: async path => { requests.push(path); return { ok: true }; },
    renderSystemHealth: () => {},
    renderEvalLab: () => assert.fail('poll must not reset evaluation data'),
    renderPermissions: () => assert.fail('poll must not recheck permissions'),
    setSync: () => {},
    toast: () => assert.fail('background poll must not show a toast')
  };
  vm.createContext(context);
  vm.runInContext(source.slice(start, end), context);
  context.updateSystemPolling();
  assert.ok(tick);
  await tick();
  await context.state.dashboard.loading.get('system');
  assert.deepEqual(requests, ['/v1/system/health']);
  context.document.hidden = true;
  context.updateSystemPolling();
  assert.equal(tick, null);
  context.document.hidden = false;
  context.state.cockpitPage = 'memory';
  context.updateSystemPolling();
  assert.equal(tick, null);
  context.state.cockpitPage = 'system';
  hiddenView = true;
  context.updateSystemPolling();
  assert.equal(tick, null);
});
