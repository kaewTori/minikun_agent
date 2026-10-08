"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const test = require("node:test");

test("preview switches views, keeps messages as text, and resets without a backend", () => {
  const element = () => ({
    hidden: false, value: "", children: [], attributes: {}, events: {},
    addEventListener(name, action) { this.events[name] = action; },
    setAttribute(name, value) { this.attributes[name] = value; },
    removeAttribute(name) { delete this.attributes[name]; },
    append(...children) { this.children.push(...children); },
    replaceChildren() { this.children = []; },
    focus() {},
    set innerHTML(_) { assert.fail("User input must never be parsed as HTML"); }
  });
  const elements = Object.fromEntries(["chat", "today", "page-name", "message-input", "new-chat", "messages", "welcome", "chat-form", "agenda"]
    .map(id => ["#" + id, element()]));
  const navigation = ["chat", "today"].map(view => Object.assign(element(), { dataset: { view } }));
  const prompt = Object.assign(element(), { dataset: { prompt: "วางแผนวันนี้" } });
  const document = {
    querySelector: selector => elements[selector],
    querySelectorAll: selector => selector === "[data-view]" ? navigation : [prompt],
    createElement: element
  };
  const html = fs.readFileSync("src/main/resources/static/cockpit/design-preview.html", "utf8");
  const media = { matches: true, addEventListener(_, action) { this.change = action; } };
  vm.runInNewContext(html.match(/<script>([\s\S]*?)<\/script>/)[1], { document, window: { matchMedia: () => media } });
  assert.equal(elements["#agenda"].open, false);
  media.matches = false;
  media.change();
  assert.equal(elements["#agenda"].open, true);

  navigation[1].events.click();
  assert.equal(elements["#chat"].hidden, true);
  assert.equal(elements["#today"].hidden, false);
  assert.equal(navigation[1].attributes["aria-current"], "page");
  prompt.events.click();
  assert.equal(elements["#today"].hidden, true);
  assert.equal(elements["#message-input"].value, "วางแผนวันนี้");

  const submit = () => elements["#chat-form"].events.submit({ preventDefault() {} });
  elements["#message-input"].value = "  ";
  submit();
  assert.equal(elements["#messages"].children.length, 0);
  elements["#message-input"].value = '<img src=x onerror="alert(1)">';
  submit();
  assert.equal(elements["#messages"].children[0].children[1].textContent, '<img src=x onerror="alert(1)">');
  assert.equal(elements["#messages"].children.length, 2);
  assert.match(elements["#messages"].children[1].children[0].textContent, /คำตอบตัวอย่าง/);
  elements["#new-chat"].events.click();
  assert.equal(elements["#messages"].children.length, 0);
  assert.equal(elements["#messages"].hidden, true);
  assert.equal(elements["#welcome"].hidden, false);
});
