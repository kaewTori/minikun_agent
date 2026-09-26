const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

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
  assert.equal(context.shouldUseBackgroundChat('ช่วยสร้างงานอ่านหนังสือพรุ่งนี้', ''), true);
  assert.equal(context.shouldUseBackgroundChat('คำตอบเดิมที่กำลังทำต่อ', 'job-123'), true);
});

test('pending image status stays below the story after text arrives', () => {
  const source = fs.readFileSync('src/main/resources/static/cockpit/cockpit.js', 'utf8');
  const start = source.indexOf('  function renderMessage(');
  const end = source.indexOf('\n  function renderChat(', start);
  const context = {
    element: (tag, className, text) => ({
      tag, className, textContent: text || '', dataset: {}, children: [],
      append(...children) { this.children.push(...children); },
      setAttribute(name, value) { this[name] = value; }
    }),
    markdown: text => `<p>${text}</p>`,
    renderVisualGallery: () => null,
    renderSourceCards: () => null,
    renderResponseMeta: () => null
  };
  vm.createContext(context);
  const progress = source.slice(source.indexOf('  function renderLiveProgress('), source.indexOf('\n  function renderVisualGallery('));
  vm.runInContext(`${progress}${source.slice(start, end)}; this.renderMessage = renderMessage;`, context);

  const row = context.renderMessage({ id: 'answer', role: 'assistant', content: 'เรื่องจบแล้ว' }, true);
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

test('elapsed timer does not replace image generation status', () => {
  const source = fs.readFileSync('src/main/resources/static/cockpit/cockpit.js', 'utf8');
  const start = source.indexOf('  function startPendingProgress(');
  const end = source.indexOf('\n  function autoGrowComposer(', start);
  const labels = [];
  let tick;
  const context = {
    updatePendingProgress: (_, label) => labels.push(label),
    window: { setInterval: callback => { tick = callback; return 1; } }
  };
  vm.createContext(context);
  vm.runInContext(`${source.slice(start, end)}; this.startPendingProgress = startPendingProgress;`, context);
  const task = { status: 'กำลังสร้างภาพประกอบ…', assistant: {
    content: '', timing: { startedAt: Date.now() }
  } };
  context.startPendingProgress(task, 'แต่งเรื่อง');
  task.assistant.content = 'เรื่องจบแล้ว';
  tick();

  assert.equal(labels.at(-1), 'กำลังสร้างภาพประกอบ…');
});
