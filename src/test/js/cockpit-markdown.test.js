"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const markdown = require("../../main/resources/static/cockpit/cockpit-markdown.js");

test("renders escaped model markdown as headings, rules, links, and a real table", () => {
  const source = String.raw`**ข่าวการเงินวันนี้มีอะไรน่าสนใจบ้าง?**
มินิคุงจัดให้ครับ พี่สาว! 📊
\---
\### 1. ข่าวเศรษฐกิจ — 29 สิงหาคม 2569 (วันนี้)
**ราคาทองคำ** ยังทรงตัว (อ้างอิง: [Vietnam Finance](https://www.vietnam.vn/th/example))
\---
\### 2. สรุปสั้นๆ
\| หัวข้อ | สถานะ |\
\|--------|-------|\
\| ทองคำ | ทรงตัว → นักลงทุนมองบวก |\
\| ฝน | เพิ่มหนัก ปลายส.ค. |`;

  const html = markdown.render(source);

  assert.match(html, /<h3>1\. ข่าวเศรษฐกิจ/);
  assert.match(html, /<hr>/);
  assert.match(html, /<a href="https:\/\/www\.vietnam\.vn\/th\/example"/);
  assert.match(html, /<div class="markdown-table-wrap" data-mobile-layout="cards"><table data-mobile-layout="cards">/);
  assert.match(html, /<th>หัวข้อ<\/th>/);
  assert.match(html, /<td data-label="หัวข้อ">ทองคำ<\/td><td data-label="สถานะ">ทรงตัว → นักลงทุนมองบวก<\/td>/);
  assert.doesNotMatch(html, /\\(?:###|---|\|)/);
});

test("marks wide tables for contained mobile scrolling", () => {
  const html = markdown.render("| A | B | C | D |\n|---|---|---|---|\n| 1 | 2 | 3 | 4 |");

  assert.match(html, /<div class="markdown-table-wrap" data-mobile-layout="scroll"><table data-mobile-layout="scroll">/);
});

test("escapes HTML and unsafe links", () => {
  const html = markdown.render('<img src=x onerror=alert(1)> [unsafe](javascript:alert(1))');

  assert.match(html, /&lt;img src=x onerror=alert\(1\)&gt;/);
  assert.doesNotMatch(html, /<img|href=/);
});

test("keeps fenced code literal", () => {
  const html = markdown.render("```html\n<script>alert('x')</script>\\\n```");

  assert.match(html, /<pre><code>&lt;script&gt;alert\(&#039;x&#039;\)&lt;\/script&gt;\\<\/code><\/pre>/);
  assert.doesNotMatch(html, /<script>/);
});
