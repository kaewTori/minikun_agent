const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

test('Cockpit remembers each content pane position without scrolling the document', () => {
  const source = fs.readFileSync('src/main/resources/static/cockpit/cockpit.js', 'utf8');
  const start = source.indexOf('  function switchCockpitPage(');
  const end = source.indexOf('\n  function markdown(', start);
  const pane = { scrollTop: 170 };
  const scroll = new Map();
  const target = { scrollIntoView(options) { this.options = options; } };
  const context = {
    state: { cockpitPage: 'today', dashboard: { scroll } },
    $: () => pane,
    loadDashboard: async () => {}, updateSystemPolling() {}, toast() {},
    requestAnimationFrame: callback => callback(),
    document: { querySelectorAll: () => [], querySelector: () => null, getElementById: () => target },
    window: { matchMedia: () => ({ matches: true }), scrollTo: () => assert.fail('Document must stay fixed') }
  };
  vm.createContext(context);
  vm.runInContext(source.slice(start, end), context);
  context.switchCockpitPage('agent');
  assert.equal(scroll.get('today'), 170);
  assert.equal(pane.scrollTop, 0);
  pane.scrollTop = 320;
  context.switchCockpitPage('today');
  assert.equal(scroll.get('agent'), 320);
  assert.equal(pane.scrollTop, 170);
  pane.scrollTop = 240;
  context.switchCockpitPage('today');
  assert.equal(pane.scrollTop, 240);
  context.switchCockpitPage('agent', 'task-center');
  assert.equal(target.options.behavior, 'auto');
  assert.equal(target.options.block, 'start');
});

test('voice settings open as a native dialog without scrolling the room', () => {
  const source = fs.readFileSync('src/main/resources/static/cockpit/voice-room.js', 'utf8');
  const start = source.indexOf('  function openVoiceSettings()');
  const end = source.indexOf('\n  selectConversation();', start);
  const deck = { open: false, scrollIntoView: () => assert.fail('Room controls must remain in place') };
  const dialog = { open: false, opens: 0, showModal() { this.open = true; this.opens++; } };
  const context = { $: selector => selector === '#voice-deck' ? deck : dialog };
  vm.createContext(context);
  vm.runInContext(source.slice(start, end), context);
  context.openVoiceSettings();
  context.openVoiceSettings();
  assert.equal(deck.open, true);
  assert.equal(dialog.open, true);
  assert.equal(dialog.opens, 1);
});
