"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
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

test("uses short labels without changing encoded paths, query parameters, or parentheses", () => {
  const url = 'https://www.facebook.com/prberd/posts/' + '%E0%B8%94'.repeat(50) + '?a=1&b=2';
  const html = markdown.render(`[${url}](${url})\nhttps://en.wikipedia.org/wiki/Function_(mathematics).`);
  assert.ok(html.includes(`href="${url.replace('&', '&amp;')}"`));
  assert.match(html, />facebook\.com<\/a>/);
  assert.match(html, /href="https:\/\/en.wikipedia.org\/wiki\/Function_\(mathematics\)"/);
  assert.doesNotMatch(html, />https?:/);
  assert.doesNotMatch(html, /target="_blank"/);
  assert.deepEqual(markdown.extractSources(`[บทความ DCA](${url})`), [{url, title:'บทความ DCA'}]);
  assert.deepEqual(markdown.extractSources(`${url}\n[บทความ DCA](${url})\n${url}`), [{url, title:'บทความ DCA'}]);
});

test("rejects incomplete citations and excludes code examples from source cards", () => {
  const broken = 'https://www.facebook.com/prberd/posts/%E0%B8...';
  const html = markdown.render(`[${broken}](${broken}\n[อีกแหล่ง](https://example.com/%E0%)`);
  assert.doesNotMatch(html, /href=|%E0|https:\/\//);
  assert.match(html, /facebook\.com · ลิงก์ไม่ครบ/);
  assert.deepEqual(markdown.extractSources(`[${broken}](${broken})\n\`https://example.com/code\`\n\`\`\`\nhttps://example.com/fenced\n\`\`\``), []);
  assert.equal(markdown.safeUrl('javascript:alert(1)'), '');
  assert.equal(markdown.safeUrl('https://example.com/%xx'), '');
  assert.doesNotMatch(markdown.render('https://example.com/cut...'), /href=/);
  assert.deepEqual(markdown.extractSources('https://example.com/cut...'), []);
});

test("supports standard angle destinations and escapes untrusted labels", () => {
  const html = markdown.render('[<img src=x onerror=alert(1)>](<https://example.com/a_(b)?x=1&y=2>)');
  assert.match(html, /href="https:\/\/example.com\/a_\(b\)\?x=1&amp;y=2"/);
  assert.doesNotMatch(html, /<img|<script/);
  assert.match(html, /&lt;img/);
});

test("source cards discard ghost URLs, keep descriptive labels, and open directly", () => {
  const source = fs.readFileSync("src/main/resources/static/cockpit/cockpit.js", "utf8");
  const start = source.indexOf("  function renderSourceCards(");
  const end = source.indexOf("\n  async function setMessageFeedback(", start);
  const context = {
    window: { MinikunMarkdown: markdown },
    safeExternalUrl: markdown.safeUrl, extractSources: markdown.extractSources,
    element: (tag, className, textContent) => ({tag, className, textContent, children: [],
      append(...children) { this.children.push(...children); }})
  };
  vm.createContext(context);
  vm.runInContext(source.slice(start, end), context);
  assert.equal(context.renderSourceCards({role:'assistant', sources:[null, {}, {url:'https://example.com/cut...'}]}), null);
  const card = context.renderSourceCards({role:'assistant',
    sources:[{url:'https://example.com/article', title:'https://example.com/article'}],
    content:'[บทความต้นฉบับ](https://example.com/article)'});
  const links = card.children[1].children;
  assert.equal(links.length, 1);
  assert.equal(links[0].textContent, 'บทความต้นฉบับ');
  assert.equal(links[0].href, 'https://example.com/article');
  assert.equal(links[0].target, '_self');
});
