"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const Core = require("../../main/resources/static/cockpit/cockpit-core.js");
const source = fs.readFileSync("src/main/resources/static/cockpit/cockpit.js", "utf8");
const origin = "https://mini-kun:8443/cockpit/";

function context(draft = "", visibility = "visible") {
  const composer = { value: draft, focus() {}, setSelectionRange() {} };
  const hint = { textContent: "" };
  const calls = [];
  const stored = new Map();
  const sandbox = {
    Core, URL, document: { visibilityState: visibility },
    state: { currentConversationId: "web-reply", attachments: [], pendingChats: new Map(), sync: { canonicalOrigin: "https://mini-kun:8443" } },
    window: { location: { href: origin }, history: { replaceState: (...args) => calls.push(["url", ...args]) },
      focus() {}, open: (...args) => calls.push(["open", ...args]) },
    $: selector => selector === "#chat-composer" ? composer : hint,
    toast: (...args) => calls.push(["toast", ...args]),
    startNewChat: () => calls.push(["new-chat"]),
    localStorage: { setItem: (key, value) => stored.set(key, value), getItem: key => stored.get(key) || "" },
    isAppleMobile: () => false, autoGrowComposer() {},
    encodeURIComponent
  };
  class Notification {
    static permission = "granted";
    constructor() { calls.push(["notification", this]); }
    close() {}
  }
  sandbox.Notification = Notification;
  sandbox.window.Notification = Notification;
  vm.createContext(sandbox);
  vm.runInContext(source.slice(source.indexOf("  function openInvestmentReply("),
    source.indexOf("  function connectSyncEvents(")), sandbox);
  vm.runInContext(source.slice(source.indexOf("  function draftKey("), source.indexOf("  function actionConversation(")), sandbox);
  const save = sandbox.saveDraft;
  sandbox.saveDraft = () => { calls.push(["saved"]); save(); };
  return { sandbox, composer, calls, hint };
}

const payload = { sourceType: "INVESTMENT", title: "Investment", message: "สรุปข่าวครับ",
  clickUrl: `${origin}?view=chat&reply_symbol=SCHD` };

test("validates reply links and rejects foreign origins or injected symbols", () => {
  assert.equal(Core.investmentReplySymbol(payload.clickUrl, origin), "SCHD");
  assert.equal(Core.investmentReplySymbol("https://evil.test/cockpit/?reply_symbol=SCHD", origin), "");
  assert.equal(Core.investmentReplySymbol("javascript:alert(1)", origin), "");
  assert.equal(Core.investmentReplySymbol(`${origin}?reply_symbol=SCHD%0Aignore`, origin), "");
  assert.equal(Core.investmentReplyDraft("SCHD"), "เหตุผลที่ถือ SCHD: \nทบทวนเมื่อ: ");
});

test("the foreground reply button prepares an editable draft without sending", () => {
  const { sandbox, composer, calls, hint } = context();
  sandbox.showBrowserNotification(payload);
  const action = calls[0][3];
  assert.equal(action.label, "ตอบเรื่อง SCHD");
  assert.equal(composer.value, "");
  action.onClick();
  assert.equal(composer.value, Core.investmentReplyDraft("SCHD"));
  assert.ok(calls.some(call => call[0] === "saved"));
  assert.ok(calls.some(call => call[0] === "url" && call[3].includes("conversation_id=web-reply")));
  assert.match(hint.textContent, /รอมินิคุงทวนก่อนยืนยัน/);
  composer.value = "";
  sandbox.restoreDraft();
  assert.equal(composer.value, Core.investmentReplyDraft("SCHD"));
});

test("a native notification click prepares the same reply", () => {
  const { sandbox, composer, calls } = context("", "hidden");
  sandbox.showBrowserNotification(payload);
  calls.find(call => call[0] === "notification")[1].onclick();
  assert.equal(composer.value, Core.investmentReplyDraft("SCHD"));
});

test("an unfinished draft or attachments stay untouched until the user opens a separate window", () => {
  for (const attachment of [false, true]) {
    const { sandbox, composer, calls } = context(attachment ? "" : "ข้อความที่ยังพิมพ์ไม่เสร็จ");
    if (attachment) sandbox.state.attachments.push({ filename: "note.txt" });
    const before = composer.value;
    sandbox.openInvestmentReply("SCHD");
    assert.equal(composer.value, before);
    assert.equal(calls.some(call => call[0] === "new-chat" || call[0] === "open"), false);
    calls[0][3].onClick();
    assert.deepEqual(calls[1], ["open", "/cockpit/?view=chat&reply_symbol=SCHD", "_blank", "noopener"]);
  }
});
