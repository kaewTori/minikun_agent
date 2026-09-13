"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const core = require("../../main/resources/static/cockpit/cockpit-core.js");

test("normalizes chat data once and keeps sync payload safe", () => {
  const [conversation] = core.normalizeConversations([{
    id: "chat-1",
    title: "วันนี้",
    updatedAt: "2026-09-13T00:00:00.000Z",
    messages: [{ id: "local", role: "user", content: "draft", localOnly: true }, {
      id: "remote", role: "assistant", content: [{ type: "text", text: "ตอบแล้ว" }],
      attachments: [{ url: "data:image/png;base64,hidden" }, { assetId: "asset-1", title: "ภาพ" }]
    }]
  }]);

  assert.equal(conversation.messages[1].content, "ตอบแล้ว");
  assert.equal(core.syncPayload(conversation).messages.length, 1);
  assert.equal(core.compactMessages(conversation.messages)[1].attachments.length, 1);
  assert.equal(core.compactMessages(conversation.messages)[1].attachments[0].assetId, "asset-1");
});

test("builds owner-scoped requests without duplicating auth headers", () => {
  const path = core.withOwner("/v1/status?limit=5", "owner/a");
  const headers = core.authHeaders("secret", true);

  assert.match(path, /limit=5/);
  assert.match(path, /ownerId=owner%2Fa/);
  assert.match(path, /owner_id=owner%2Fa/);
  assert.equal(headers["Content-Type"], "application/json");
  assert.equal(headers["X-Minikun-Model-Token"], "secret");
});
