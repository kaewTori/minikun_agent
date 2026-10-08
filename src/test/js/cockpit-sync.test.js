"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const source = fs.readFileSync("src/main/resources/static/cockpit/cockpit.js", "utf8");

function syncContext(fetch) {
  const stored = new Map();
  const conversation = { id: "chat", title: "local", updatedAt: 1, messages: [] };
  const state = {
    conversations: [conversation], currentConversationId: "chat", chatMessages: [],
    pendingChats: new Map(),
    sync: { paired: true, pending: new Set(), revision: 0, timers: new Map(),
      inFlight: new Map(), refreshing: false, remoteDirty: false }
  };
  const context = {
    state,
    Core: require("../../main/resources/static/cockpit/cockpit-core.js"),
    localStorage: { setItem: (key, value) => stored.set(key, value), getItem: key => stored.get(key) || null },
    window: { clearTimeout() {}, setTimeout() { return 1; } },
    queueMicrotask() {},
    $: () => null,
    toast() {},
    persistConversations() {},
    renderConversationList() {},
    renderChat() {},
    loadChatActivity() {},
    syncChatState() {},
    resumeBackgroundChats() {},
    chatId: () => "new"
  };
  vm.createContext(context);
  vm.runInContext(source.slice(source.indexOf("  function markConversationDirty("),
    source.indexOf("  function showBrowserNotification(")), context);
  context.syncFetch = fetch;
  return { context, state, stored, conversation };
}

test("failed upload keeps local chat and its retry queue", async () => {
  let reads = 0;
  const { context, state, stored } = syncContext(async (path, options) => {
    if (options?.method === "PUT") throw new Error("offline");
    reads += 1;
    return [];
  });
  context.markConversationDirty("chat");
  assert.equal(await context.pushConversationSync("chat"), false);
  await context.refreshSyncedConversations();
  assert.equal(reads, 0);
  assert.equal(state.conversations[0].title, "local");
  assert.equal(stored.get("minikun.sync-pending"), '["chat"]');
});

test("an older refresh cannot replace an edit made while it loads", async () => {
  let finishRead;
  const { context, state, conversation } = syncContext(() => new Promise((resolve) => { finishRead = resolve; }));
  const refresh = context.refreshSyncedConversations();
  conversation.title = "new edit";
  context.markConversationDirty("chat");
  finishRead([{ id: "chat", title: "old remote", updatedAt: 1, messages: [] }]);
  await refresh;
  assert.equal(state.conversations[0].title, "new edit");
  assert.equal(state.sync.remoteDirty, true);
});

test("remote deletion clears the open conversation", async () => {
  const { context, state } = syncContext(async () => []);
  state.chatMessages = [{ id: "old", content: "deleted" }];
  await context.refreshSyncedConversations();
  assert.equal(state.conversations.length, 0);
  assert.equal(state.chatMessages.length, 0);
  assert.equal(state.currentConversationId, "new");
});

test("sync preserves the reply conversation while its unsent draft is only local", async () => {
  const { context, state, stored } = syncContext(async () => [{ id: "other", messages: [] }]);
  stored.set("minikun.draft.chat", "เหตุผลที่ถือ SCHD: อยากลงทุนระยะยาว\nทบทวนเมื่อ: ");
  await context.refreshSyncedConversations();
  assert.equal(state.currentConversationId, "chat");
  assert.match(stored.get("minikun.draft.chat"), /อยากลงทุนระยะยาว/);
});

test("voice sync reuses the saved timestamp and clears its retry entry", async () => {
  const voiceSource = fs.readFileSync("src/main/resources/static/cockpit/voice-room.js", "utf8");
  const stored = new Map([["minikun.conversations", JSON.stringify([{
    id: "chat", title: "voice", updatedAt: 123, messages: []
  }])]]);
  let tick = 1000;
  let uploaded;
  const context = {
    Core: require("../../main/resources/static/cockpit/cockpit-core.js"),
    Date: class extends Date { static now() { return ++tick; } },
    state: { conversationId: "chat", messages: [{ id: "message", role: "user", content: "hello", createdAt: 1 }], paired: false },
    localStorage: { getItem: key => stored.get(key), setItem: (key, value) => stored.set(key, value) },
    loadLocalConversations: () => JSON.parse(stored.get("minikun.conversations")),
    conversationTitle: () => "voice",
    updateConversationCount() {},
    syncStatus: { textContent: "" },
    syncApi: async (_, request) => { uploaded = request.json; }
  };
  vm.createContext(context);
  vm.runInContext(voiceSource.slice(voiceSource.indexOf("  function localPayload("),
    voiceSource.indexOf("  function useRemoteConversation(")), context);
  assert.equal(context.localPayload().updatedAt, 123);
  context.saveLocal("hello");
  const savedTime = JSON.parse(stored.get("minikun.conversations"))[0].updatedAt;
  assert.equal(context.syncPayload().updatedAt, new Date(savedTime).toISOString());
  assert.equal(stored.get("minikun.sync-pending"), '["chat"]');
  context.state.paired = true;
  assert.equal(await context.syncConversation(), true);
  assert.equal(uploaded.updatedAt, new Date(savedTime).toISOString());
  assert.equal(stored.get("minikun.sync-pending"), "[]");
});

test("feedback uses the server response id and preserves the chosen category", async () => {
  const calls = [];
  const context = {
    state: { ownerId: "owner", currentConversationId: "chat", chatMessages: [] },
    requestFeedback: async () => ({ category: "FACT_WRONG", reason: "ข้อมูลผิด" }),
    saveConversation() {}, renderChat() {}, toast() {},
    api: async (path, options) => { calls.push({ path, options }); }
  };
  vm.createContext(context);
  vm.runInContext(source.slice(source.indexOf("  async function setMessageFeedback("),
    source.indexOf("  function renderLiveProgress(")), context);
  const oldMessage = { id: "message-local", feedback: "" };
  await context.setMessageFeedback(oldMessage, "down");
  assert.equal(calls.length, 0);
  const message = { id: "message-local", responseId: "chatcmpl-real", feedback: "" };
  await context.setMessageFeedback(message, "down");
  assert.equal(calls.length, 1);
  const body = JSON.parse(calls[0].options.body);
  assert.equal(body.message_id, "chatcmpl-real");
  assert.equal(body.category, "FACT_WRONG");
  const core = require("../../main/resources/static/cockpit/cockpit-core.js");
  const stored = core.syncPayload({ id: "chat", title: "test", updatedAt: 1, messages: [message] });
  assert.equal(core.normalizeMessage(stored.messages[0]).feedbackCategory, "FACT_WRONG");
});
