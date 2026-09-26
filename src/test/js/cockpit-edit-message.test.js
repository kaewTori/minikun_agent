const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

test('editing a question reruns it in the same conversation', async () => {
  const source = fs.readFileSync('src/main/resources/static/cockpit/cockpit.js', 'utf8');
  const messages = [
    { id: 'u1', role: 'user', content: 'first' },
    { id: 'a1', role: 'assistant', content: 'answer one' },
    { id: 'u2', role: 'user', content: 'old question' },
    { id: 'a2', role: 'assistant', content: 'old answer' }
  ];
  const state = { currentConversationId: 'chat', chatMessages: messages, pendingChats: new Map() };
  let run;
  const notices = [];
  const context = {
    state,
    chatId: () => assert.fail('editing must not create a conversation'),
    uniqueId: () => 'u2-edited',
    requestEditedMessage: async () => 'new question',
    hydrateMessageAttachments: async () => [],
    requestContent: (content) => content,
    renderChat() {}, renderConversationList() {},
    runChatTurn: async (options) => { run = options; },
    toast: (message) => notices.push(message)
  };
  vm.createContext(context);
  vm.runInContext(source.slice(source.indexOf('  async function rerunFromUser('),
    source.indexOf('  function speechText(')), context);

  await context.editUserMessage(2);
  assert.equal(state.currentConversationId, 'chat');
  assert.deepEqual(state.chatMessages.map((message) => message.id), ['u1', 'a1', 'u2-edited']);
  assert.equal(state.chatMessages[2].content, 'new question');
  assert.equal(run.conversationId, 'chat');
  assert.equal(run.messages, state.chatMessages);
  assert.equal(run.userMessage, state.chatMessages[2]);
  assert.equal(run.currentContent, 'new question');
  assert.deepEqual(notices, []);

  state.pendingChats.set('chat', {});
  await context.rerunFromUser(0, false, 'blocked edit');
  assert.deepEqual(state.chatMessages.map((message) => message.id), ['u1', 'a1', 'u2-edited']);
  assert.match(notices[0], /รอให้มินิคุงตอบ/);
});
