const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

test('explicit regeneration starts a fresh background attempt while reload resumes the saved job', async () => {
  const source = fs.readFileSync('src/main/resources/static/cockpit/cockpit.js', 'utf8');
  const original = { id: 'original', role: 'user', content: 'สร้าง PowerPoint', backgroundJobId: 'old-job' };
  const state = { currentConversationId: 'chat', chatMessages: [original, { role: 'assistant', parentId: 'original' }],
    pendingChats: new Map(), conversations: [] };
  const calls = [];
  let nextId = 0;
  const context = { state, uniqueId: () => `attempt-${++nextId}`, renderChat() {}, renderConversationList() {},
    hydrateMessageAttachments: async () => [], requestContent: text => text,
    runChatTurn: async args => calls.push(args) };
  vm.createContext(context);
  const start = source.indexOf('  async function rerunFromUser(');
  const end = source.indexOf('\n  function userBefore(', start);
  const resumeStart = source.indexOf('  function resumeBackgroundChats(');
  const resumeEnd = source.indexOf('\n  async function sendChat(', resumeStart);
  vm.runInContext(`${source.slice(start, end)}${source.slice(resumeStart, resumeEnd)}
    this.rerunFromUser = rerunFromUser; this.resumeBackgroundChats = resumeBackgroundChats;`, context);
  await context.rerunFromUser(0);
  assert.equal(calls[0].userMessage.id, 'attempt-1');
  assert.equal(calls[0].userMessage.backgroundJobId, '');
  assert.equal(calls[0].userMessage.content, original.content);
  assert.equal(original.id, 'original');
  await context.rerunFromUser(0);
  assert.equal(calls[1].userMessage.id, 'attempt-2');
  state.conversations = [{ id: 'pending-chat', messages: [original] }];
  context.resumeBackgroundChats();
  assert.equal(calls[2].userMessage.id, 'original');
  assert.equal(calls[2].backgroundJobId, 'old-job');
});

test('ordinary chat streams while durable work stays resumable', () => {
  const source = fs.readFileSync('src/main/resources/static/cockpit/cockpit.js', 'utf8');
  const start = source.indexOf('  function requiresDurableBackground');
  const end = source.indexOf('\n  function updateStreamingMessage', start);
  const context = {};
  vm.createContext(context);
  vm.runInContext(`${source.slice(start, end)}; this.shouldUseBackgroundChat = shouldUseBackgroundChat;`, context);

  assert.equal(context.shouldUseBackgroundChat('ช่วยอธิบายเรื่องนี้หน่อย', ''), false);
  assert.equal(context.shouldUseBackgroundChat('ช่วยทำงานเบื้องหลังให้หน่อย', ''), true);
  assert.equal(context.shouldUseBackgroundChat('ช่วยสร้างภาพแมวสีส้ม', ''), true);
  assert.equal(context.shouldUseBackgroundChat('ทำเป็นสไลด์ให้เราหน่อยนะ', ''), true);
  assert.equal(context.shouldUseBackgroundChat('ช่วยทำ PowerPoint 8 หน้าให้หน่อย', ''), true);
  assert.equal(context.shouldUseBackgroundChat('create a slide deck about PostgreSQL', ''), true);
  assert.equal(context.shouldUseBackgroundChat('ช่วยทำอินโฟกราฟิกประหยัดไฟ', ''), true);
  assert.equal(context.shouldUseBackgroundChat('มินิคุง ช่วยทำแผนภาพเกี่ยวกับตัวมินิคุงมาให้เราหน่อย', ''), true);
  assert.equal(context.shouldUseBackgroundChat('อยากได้วิธีคิดของมินิคุง ในรูปแบบ info', ''), true);
  assert.equal(context.shouldUseBackgroundChat('ปรับให้ลองทำเป็น flowchart', ''), true);
  assert.equal(context.shouldUseBackgroundChat('เปลี่ยนเป็น flowchart', ''), true);
  assert.equal(context.shouldUseBackgroundChat('ช่วยสร้างงานอ่านหนังสือพรุ่งนี้', ''), true);
  assert.equal(context.shouldUseBackgroundChat('คำตอบเดิมที่กำลังทำต่อ', 'job-123'), true);
});

test('pending image status stays below the story after text arrives', () => {
  const source = fs.readFileSync('src/main/resources/static/cockpit/cockpit.js', 'utf8');
  const start = source.indexOf('  function renderMessage(');
  const end = source.indexOf('\n  function renderChat(', start);
  const context = {
    state: { ownerId: 'owner', chatActivity: { traces: new Map(), runs: new Map() } },
    element: (tag, className, text) => ({
      tag, className, textContent: text || '', dataset: {}, children: [],
      append(...children) { this.children.push(...children); },
      setAttribute(name, value) { this[name] = value; }
    }),
    normalizeAttachment: value => value,
    renderPresentationFiles: () => null,
    markdown: text => `<p>${text}</p>`,
    renderVisualGallery: () => null,
    renderSourceCards: () => null,
    renderResponseMeta: () => null
  };
  vm.createContext(context);
  const progress = source.slice(source.indexOf('  function renderLiveProgress('), source.indexOf('\n  function renderVisualGallery('));
  vm.runInContext(`${progress}${source.slice(start, end)}; this.renderMessage = renderMessage;`, context);

  const row = context.renderMessage({ id: 'answer', role: 'assistant', content: 'เรื่องจบแล้ว', status: 'generating' }, true);
  const body = row.children.at(-1);
  assert.equal(body.children[0].innerHTML, '<p>เรื่องจบแล้ว</p>');
  assert.equal(body.children[1].className, 'live-progress');
  assert.equal(body.children[1].children[0].textContent, 'กำลังทำความเข้าใจคำถาม');
  assert.equal(body.children[1].children[1].innerHTML, '<i></i><i></i><i></i>');
  assert.equal(body.children[1].children[1]['aria-hidden'], 'true');
  assert.equal(body.children[1].children.length, 2);
});

test('stream image phase updates the pending status', async () => {
  const source = fs.readFileSync('src/main/resources/static/cockpit/cockpit.js', 'utf8');
  const start = source.indexOf('  async function streamChatCompletion(');
  const end = source.indexOf('\n  async function runChatTurn(', start);
  const labels = [];
  let read = false;
  const context = {
    Core: { withOwner: path => path }, state: { ownerId: 'owner' }, headers: () => ({}),
    fetch: async () => ({ ok: true, body: { getReader: () => ({
      read: async () => {
        if (read) return { done: true };
        read = true;
        return { done: false, value: new TextEncoder().encode('data: {"image_status":"running","choices":[]}\n\n') };
      }
    }) } }),
    TextDecoder, updatePendingProgress: (_, label) => labels.push(label),
    updateStreamingMessage: () => {}, normalizeVisual: value => value,
    normalizeAttachment: value => value,
    window: { clearInterval: () => {} }
  };
  vm.createContext(context);
  vm.runInContext(`${source.slice(start, end)}; this.streamChatCompletion = streamChatCompletion;`, context);
  await context.streamChatCompletion({
    conversationId: 'chat', controller: { signal: {} }, assistant: {
      attachments: [], timing: { firstTokenMs: 0 }, content: ''
    }
  }, {});

  assert.deepEqual(labels, ['กำลังสร้างภาพประกอบ…']);
});

test('pending status does not invent elapsed-time stages', () => {
  const source = fs.readFileSync('src/main/resources/static/cockpit/cockpit.js', 'utf8');
  const start = source.indexOf('  function startPendingProgress(');
  const end = source.indexOf('\n  function autoGrowComposer(', start);
  const labels = [];
  const context = {
    updatePendingProgress: (_, label) => labels.push(label)
  };
  vm.createContext(context);
  vm.runInContext(`${source.slice(start, end)}; this.startPendingProgress = startPendingProgress;`, context);
  const task = { status: 'กำลังประมวลผล…', assistant: { content: '' } };
  context.startPendingProgress(task, 'แต่งเรื่อง');
  assert.deepEqual(labels, ['กำลังประมวลผล…']);
});

test('work trace shows an honest fallback and uses only records for the exact response', () => {
  const source = fs.readFileSync('src/main/resources/static/cockpit/cockpit.js', 'utf8');
  const start = source.indexOf('  function renderWorkTrace(');
  const end = source.indexOf('\n  async function loadChatActivity(', start);
  const trace = { responseId: 'reply-2', summary: 'ใช้ข้อมูลจากระบบ', tools: ['guardian.check'],
    sources: ['SYSTEM:health'], decisions: { turn_intent: 'technical' } };
  const detail = { run: { responseId: 'reply-2', status: 'COMPLETED', objective: 'ตรวจระบบ', plannedSteps: ['ตรวจ', 'สรุป'] },
    steps: [{ toolName: 'guardian.check', status: 'COMPLETED', argumentsJson: 'secret-input', resultJson: 'secret-output' }] };
  const state = { ownerId: 'owner', chatMessages: [
    { id: 'question-1', role: 'user', content: 'เพลงนี้เกี่ยวกับอะไร' },
    { id: 'message-1', role: 'assistant', parentId: 'question-1' },
    { id: 'message-2', role: 'assistant', parentId: 'question-1' }
  ], chatActivity: { traces: new Map([['owner:reply-2', trace]]),
    runs: new Map([['owner:reply-2', detail]]), open: new Set() } };
  const context = { state, formatDuration: () => '50 วิ', element: (tag, className, value) => ({
    tag, className, textContent: value || '', children: [],
    append(...children) { this.children.push(...children); }, addEventListener() {}, setAttribute() {}
  }) };
  vm.createContext(context);
  vm.runInContext(`${source.slice(start, end)}; this.renderWorkTrace = renderWorkTrace;`, context);

  assert.equal(context.renderWorkTrace({ role: 'user', content: 'คำถาม' }), null);
  const fallback = context.renderWorkTrace({ id: 'message-1', role: 'assistant', parentId: 'question-1',
    content: 'คำตอบ', usage: { promptTokens: 2770, completionTokens: 445 }, timing: { totalMs: 50000 } });
  assert.match(JSON.stringify(fallback), /เพลงนี้เกี่ยวกับอะไร/);
  assert.match(JSON.stringify(fallback), /2,770 โทเคน|2,770|๒,๗๗๐/);
  assert.match(JSON.stringify(fallback), /445 โทเคน|445|๔๔๕/);
  assert.match(JSON.stringify(fallback), /ไม่มีบันทึกแผนหรือเครื่องมือย้อนหลัง/);
  assert.doesNotMatch(JSON.stringify(fallback), /guardian\.check/);
  const card = context.renderWorkTrace({ id: 'message-2', role: 'assistant', parentId: 'question-1',
    content: 'คำตอบ', responseId: 'reply-2' });
  const visibleText = JSON.stringify(card);
  assert.match(visibleText, /ตรวจระบบ/);
  assert.match(visibleText, /วิเคราะห์เชิงเทคนิค/);
  assert.match(visibleText, /guardian\.check/);
  assert.doesNotMatch(visibleText, /secret-input|secret-output/);
});

test('activity matches responses when the server canonicalizes the conversation id', async () => {
  const source = fs.readFileSync('src/main/resources/static/cockpit/cockpit.js', 'utf8');
  const start = source.indexOf('  async function loadChatActivity(');
  const end = source.indexOf('\n  function renderVisualGallery(', start);
  const state = { ownerId: 'owner', currentConversationId: 'web-long-id',
    conversations: [{ id: 'web-long-id', messages: [{ role: 'assistant', responseId: 'reply-1' }] }],
    chatActivity: { traces: new Map(), runs: new Map() } };
  const context = { state, api: async (path) => path.includes('/explanations')
    ? [{ conversationId: 'canonical-uuid', responseId: 'reply-1', summary: 'ที่มาของคำตอบ' }]
    : [], renderChat: () => {} };
  vm.createContext(context);
  vm.runInContext(`${source.slice(start, end)}; this.loadChatActivity = loadChatActivity;`, context);
  await context.loadChatActivity('web-long-id');
  assert.equal(state.chatActivity.traces.get('owner:reply-1').summary, 'ที่มาของคำตอบ');
});
