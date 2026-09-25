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
