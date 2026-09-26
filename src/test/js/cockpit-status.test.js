const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('src/main/resources/static/cockpit/cockpit.js', 'utf8');

test('unavailable dashboard data never reads as a calm day', () => {
  const nodes = new Map();
  const context = { $: id => {
    if (!nodes.has(id)) nodes.set(id, { textContent: '', dataset: {} });
    return nodes.get(id);
  } };
  vm.createContext(context);
  vm.runInContext(source.slice(source.indexOf('  function renderStatus('), source.indexOf('  function renderActions(')), context);
  context.renderStatus(null);
  assert.equal(nodes.get('#today').dataset.state, 'unavailable');
  assert.equal(nodes.get('#waiting-count').textContent, '—');
  assert.match(nodes.get('#attention-title').textContent, /โหลด.*ไม่ได้/);
  context.renderStatus({ due_tasks: 0, open_goals: 0, agent_waiting_confirmation: 0 });
  assert.equal(nodes.get('#today').dataset.state, 'calm');
});

test('last failed reply stays visible in chat status after reload', () => {
  const labels = [];
  const context = {
    state: { chatTranscribing: false, currentConversationId: 'chat', pendingChats: new Map(), chatMessages: [{ status: 'failed' }] },
    setChatBusy: (_, label) => labels.push(label)
  };
  vm.createContext(context);
  vm.runInContext(source.slice(source.indexOf('  function syncChatState('), source.indexOf('  function updatePendingProgress(')), context);
  context.syncChatState();
  assert.equal(labels.at(-1), 'เชื่อมต่อไม่สำเร็จ');
  context.state.chatMessages = [{ status: 'complete' }];
  context.syncChatState();
  assert.equal(labels.at(-1), 'พร้อมช่วยพี่สาว');
});
