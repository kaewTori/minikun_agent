"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const source = fs.readFileSync("src/main/resources/static/cockpit/cockpit.js", "utf8");

test("an unreadable browser permission is unknown, not denied", async () => {
  const nodes = new Map();
  const context = {
    navigator: {},
    $: (selector) => {
      if (!nodes.has(selector)) nodes.set(selector, { textContent: "", dataset: {}, disabled: false });
      return nodes.get(selector);
    }
  };
  vm.createContext(context);
  vm.runInContext(source.slice(source.indexOf("  const permissionLabels ="),
    source.indexOf("  async function renderPermissions(")), context);

  assert.equal(await context.browserPermission("microphone"), "unknown");
  context.navigator.permissions = { query: async () => { throw new Error("unsupported"); } };
  assert.equal(await context.browserPermission("microphone"), "unknown");
  context.setPermissionUi("microphone", "unknown");
  assert.equal(nodes.get("#microphone-state").textContent, "ตรวจสอบกับเบราว์เซอร์");
  assert.equal(nodes.get("#request-microphone").textContent, "ทดสอบ");
});
