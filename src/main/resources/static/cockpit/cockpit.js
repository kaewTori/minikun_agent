(() => {
  "use strict";

  const state = {
    ownerId: sessionStorage.getItem("minikun.owner") || "default",
    token: sessionStorage.getItem("minikun.token") || "",
    model: localStorage.getItem("minikun.model") || "mini-kun",
    conversations: [],
    currentConversationId: "",
    conversationQuery: "",
    showArchived: false,
    actionConversationId: "",
    chatMessages: [],
    attachments: [],
    pendingChats: new Map(),
    chatTranscribing: false,
    mediaStream: null,
    audioContext: null,
    audioSource: null,
    audioProcessor: null,
    recordingChunks: [],
    recordingStartedAt: 0,
    recordingTimer: null,
    voiceOutput: localStorage.getItem("minikun.voice-output") === "true",
    activeAudio: null,
    cockpitPage: "overview",
    timelineLimit: 6,
    experimentAction: null,
    currentLocation: null,
    sync: {
      paired: false,
      deviceId: "",
      canonicalOrigin: "https://mini-kun:8443",
      timers: new Map(),
      eventSource: null,
      pairingUrl: "",
      refreshing: false,
      remoteDirty: false
    }
  };

  const $ = (selector) => document.querySelector(selector);
  const settingsDialog = $("#settings-dialog");
  const mobileMenuDialog = $("#mobile-menu-dialog");
  const experimentDialog = $("#experiment-dialog");
  const experimentActionDialog = $("#experiment-action-dialog");
  const clearHistoryDialog = $("#clear-history-dialog");

  function headers(json = false) {
    const value = {};
    if (json) value["Content-Type"] = "application/json";
    if (state.token) {
      value["X-Minikun-Personal-Token"] = state.token;
      value["X-Minikun-Task-Token"] = state.token;
      value["X-Minikun-Goal-Token"] = state.token;
      value["X-Minikun-Agent-Token"] = state.token;
      value["X-Minikun-Memory-Token"] = state.token;
      value["X-Minikun-Knowledge-Token"] = state.token;
      value["X-Minikun-System-Token"] = state.token;
    }
    return value;
  }

  async function api(path, options = {}) {
    const separator = path.includes("?") ? "&" : "?";
    const owner = encodeURIComponent(state.ownerId);
    const response = await fetch(`${path}${separator}ownerId=${owner}&owner_id=${owner}`, {
      ...options,
      headers: { ...headers(Boolean(options.body)), ...(options.headers || {}) }
    });
    if (!response.ok) {
      let message = `เชื่อมต่อไม่สำเร็จ (${response.status})`;
      try {
        const body = await response.json();
        message = body.error?.message || body.message || (typeof body.error === "string" ? body.error : message);
      } catch (_) { /* keep the status-based message */ }
      throw new Error(message);
    }
    return response.status === 204 ? null : response.json();
  }

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function thaiDate(value = new Date()) {
    return new Intl.DateTimeFormat("th-TH", { weekday: "long", day: "numeric", month: "long" }).format(value);
  }

  function relativeTime(value) {
    const date = new Date(value);
    const seconds = Math.round((date.getTime() - Date.now()) / 1000);
    const formatter = new Intl.RelativeTimeFormat("th", { numeric: "auto" });
    if (Math.abs(seconds) < 60) return formatter.format(seconds, "second");
    if (Math.abs(seconds) < 3600) return formatter.format(Math.round(seconds / 60), "minute");
    if (Math.abs(seconds) < 86400) return formatter.format(Math.round(seconds / 3600), "hour");
    return formatter.format(Math.round(seconds / 86400), "day");
  }

  function toast(message, error = false) {
    const node = element("div", `toast${error ? " error" : ""}`, message);
    $("#toast-region").append(node);
    setTimeout(() => node.remove(), 3800);
  }

  /* Chat --------------------------------------------------------------- */
  function uniqueId(prefix = "") {
    try {
      if (globalThis.crypto?.randomUUID) return `${prefix}${globalThis.crypto.randomUUID()}`;
      if (globalThis.crypto?.getRandomValues) {
        const bytes = new Uint8Array(16);
        globalThis.crypto.getRandomValues(bytes);
        return `${prefix}${Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("")}`;
      }
    } catch (_) { /* fall through for older Safari and non-secure LAN origins */ }
    return `${prefix}${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
  }

  function chatId() {
    return uniqueId("web-");
  }

  function safeConversationList() {
    try {
      const parsed = JSON.parse(localStorage.getItem("minikun.conversations") || "[]");
      return Array.isArray(parsed) ? normalizeConversations(parsed) : [];
    } catch (_) { return []; }
  }

  function persistConversations() {
    try {
      localStorage.setItem("minikun.conversations", JSON.stringify(state.conversations.slice(0, 40)));
    } catch (_) {
      toast("พื้นที่เก็บประวัติบน browser เต็มครับ แชตยังทำงานต่อได้", true);
    }
  }

  function compactMessages(messages) {
    return messages.slice(-80).map((message) => ({
      id: message.id || uniqueId("message-"),
      role: message.role,
      content: message.content,
      localOnly: Boolean(message.localOnly),
      parentId: message.parentId || "",
      branchId: message.branchId || "",
      status: message.status || "complete",
      feedback: message.feedback || "",
      feedbackReason: message.feedbackReason || "",
      sources: (message.sources || []).slice(0, 12),
      createdAt: message.createdAt,
      files: (message.files || []).slice(0, 12),
      usage: message.usage,
      timing: message.timing ? {
        firstTokenMs: message.timing.firstTokenMs,
        totalMs: message.timing.totalMs
      } : undefined,
      attachments: (message.attachments || [])
        .map((attachment) => ({
          title: attachment.title || attachment.name || "ไฟล์แนบ",
          url: attachment.url && !attachment.url.startsWith("data:") ? attachment.url : "",
          assetId: attachment.assetId || "",
          kind: attachment.kind || "image",
          type: attachment.type || ""
        }))
        .filter((attachment) => attachment.url || attachment.assetId)
        .slice(0, 6)
    }));
  }

  function normalizeConversations(values) {
    return values.slice(0, 40).filter((value) => value?.id).map((value) => ({
      id: String(value.id),
      title: String(value.title || "แชตใหม่"),
      updatedAt: Number(value.updatedAt) || Date.parse(value.updatedAt) || Date.now(),
      pinned: Boolean(value.pinned),
      archived: Boolean(value.archived),
      messages: (Array.isArray(value.messages) ? value.messages : []).slice(-80).map((message) => ({
        ...message,
        id: message.id || uniqueId("message-"),
        role: message.role || "user",
        content: String(message.content || ""),
        parentId: message.parentId || message.metadata?.parentId || "",
        branchId: message.branchId || message.metadata?.branchId || "",
        status: message.status || message.metadata?.status || "complete",
        feedback: message.feedback || message.metadata?.feedback || "",
        feedbackReason: message.feedbackReason || message.metadata?.feedbackReason || "",
        sources: Array.isArray(message.sources) ? message.sources
          : Array.isArray(message.metadata?.sources) ? message.metadata.sources : [],
        createdAt: Number(message.createdAt) || Date.parse(message.createdAt) || Date.now(),
        files: Array.isArray(message.files) ? message.files : [],
        attachments: Array.isArray(message.attachments) ? message.attachments : []
      }))
    })).sort((a, b) => b.updatedAt - a.updatedAt);
  }

  function syncPayload(conversation) {
    return {
      id: conversation.id,
      title: conversation.title,
      pinned: Boolean(conversation.pinned),
      archived: Boolean(conversation.archived),
      updatedAt: new Date(conversation.updatedAt || Date.now()).toISOString(),
      messages: (conversation.messages || []).filter((message) => !message.localOnly).map((message) => ({
        id: message.id,
        role: message.role,
        content: message.content,
        files: message.files,
        usage: message.usage,
        timing: message.timing,
        attachments: message.attachments,
        metadata: {
          parentId: message.parentId || "",
          branchId: message.branchId || "",
          status: message.status || "complete",
          feedback: message.feedback || "",
          feedbackReason: message.feedbackReason || "",
          sources: (message.sources || []).slice(0, 12)
        },
        createdAt: new Date(message.createdAt || Date.now()).toISOString()
      }))
    };
  }

  function defaultDeviceName() {
    if (/iPhone/i.test(navigator.userAgent)) return "iPhone";
    if (/iPad/i.test(navigator.userAgent)) return "iPad";
    if (/Mac/i.test(navigator.platform || navigator.userAgent)) return "Mac เครื่องหลัก";
    return "Browser เครื่องนี้";
  }

  async function syncFetch(path, options = {}) {
    const response = await fetch(path, {
      ...options,
      headers: { ...(options.body ? { "Content-Type": "application/json" } : {}), ...(options.headers || {}) }
    });
    if (!response.ok) {
      let message = `ซิงก์ไม่สำเร็จ (${response.status})`;
      try {
        const body = await response.json();
        message = body.detail || body.message || message;
      } catch (_) { /* keep status message */ }
      const error = new Error(message);
      error.status = response.status;
      throw error;
    }
    return response.status === 204 ? null : response.json();
  }

  function renderSyncState(label, stateName = "") {
    const top = $("#sync-state");
    if (top) {
      top.replaceChildren(element("span", "status-dot"), document.createTextNode(` ${label}`));
      top.dataset.state = stateName;
    }
    const badge = $("#device-sync-state");
    if (badge) {
      badge.textContent = label;
      badge.dataset.state = stateName;
    }
  }

  async function initializeSync() {
    if (window.location.protocol !== "https:") {
      renderSyncState("ต้องใช้ HTTPS", "unpaired");
      $("#device-sync-copy").textContent = "เครื่องหลักเปิดผ่าน https://127.0.0.1:8443 เพื่อซิงก์ครับ";
      return;
    }
    try {
      const session = await syncFetch("/v1/sync/session", {
        method: "POST", body: JSON.stringify({ deviceName: defaultDeviceName() })
      });
      state.sync.canonicalOrigin = session.canonicalOrigin || state.sync.canonicalOrigin;
      if (!session.paired) {
        renderSyncState("ยังไม่จับคู่", "unpaired");
        $("#device-sync-copy").textContent = "สแกน QR จากอุปกรณ์ที่เชื่อมต่ออยู่แล้วครับ";
        return;
      }
      state.sync.paired = true;
      state.sync.deviceId = session.deviceId;
      state.ownerId = session.ownerId || state.ownerId;
      sessionStorage.setItem("minikun.owner", state.ownerId);
      $("#owner-id").value = state.ownerId;
      $("#owner-id").disabled = true;
      $("#device-sync-copy").textContent = `${session.deviceName} · ข้อมูลอยู่ที่มินิคุง`;
      $("#create-pairing").disabled = false;
      renderSyncState("ซิงก์แล้ว", "paired");
      const local = state.conversations.map(syncPayload);
      if (local.length && localStorage.getItem("minikun.sync-migrated") !== "true") {
        await syncFetch("/v1/sync/conversations/import", { method: "POST", body: JSON.stringify(local) });
        localStorage.setItem("minikun.sync-migrated", "true");
      }
      await refreshSyncedConversations();
      await loadPairedDevices();
      connectSyncEvents();
      if (session.bootstrapped) toast("ตั้ง Mac เครื่องนี้เป็นอุปกรณ์หลักแล้วครับ");
    } catch (error) {
      renderSyncState("ซิงก์สะดุด", "unpaired");
      $("#device-sync-copy").textContent = error.message;
    }
  }

  async function refreshSyncedConversations() {
    if (!state.sync.paired || state.sync.refreshing) return;
    if (state.pendingChats.size) {
      state.sync.remoteDirty = true;
      return;
    }
    state.sync.refreshing = true;
    state.sync.remoteDirty = false;
    try {
      const remote = normalizeConversations(await syncFetch("/v1/sync/conversations"));
      const currentId = state.currentConversationId;
      state.conversations = remote;
      const current = remote.find((conversation) => conversation.id === currentId) || remote[0];
      if (current) {
        state.currentConversationId = current.id;
        state.chatMessages = current.messages;
      } else if (!state.chatMessages.length) {
        state.currentConversationId = chatId();
      }
      persistConversations();
      renderConversationList();
      renderChat();
      syncChatState();
    } finally {
      state.sync.refreshing = false;
    }
  }

  function scheduleConversationSync(conversationId) {
    if (!state.sync.paired) return;
    window.clearTimeout(state.sync.timers.get(conversationId));
    state.sync.timers.set(conversationId, window.setTimeout(async () => {
      state.sync.timers.delete(conversationId);
      const conversation = state.conversations.find((value) => value.id === conversationId);
      if (!conversation) return;
      try {
        await syncFetch(`/v1/sync/conversations/${encodeURIComponent(conversationId)}`, {
          method: "PUT", body: JSON.stringify(syncPayload(conversation))
        });
        renderSyncState("ซิงก์แล้ว", "paired");
      } catch (error) {
        renderSyncState("รอซิงก์", "unpaired");
      }
    }, 220));
  }

  function connectSyncEvents() {
    state.sync.eventSource?.close();
    const source = new EventSource("/v1/sync/events");
    state.sync.eventSource = source;
    source.addEventListener("sync", async (event) => {
      try {
        const update = JSON.parse(event.data);
        if (update.sourceDeviceId === state.sync.deviceId) return;
        await refreshSyncedConversations();
        await loadPairedDevices();
        renderSyncState("อัปเดตแล้ว", "paired");
      } catch (_) { /* EventSource reconnects and the next refresh repairs state */ }
    });
    source.onerror = () => renderSyncState("กำลังเชื่อมใหม่", "unpaired");
    source.onopen = () => renderSyncState("ซิงก์แล้ว", "paired");
  }

  async function loadPairedDevices() {
    const list = $("#paired-device-list");
    if (!list || !state.sync.paired) return;
    try {
      const devices = await syncFetch("/v1/sync/devices");
      list.replaceChildren();
      for (const device of devices) {
        const row = element("div", "paired-device");
        const copy = element("div");
        copy.append(element("strong", "", device.name));
        copy.append(element("small", "", `ใช้งาน ${relativeTime(device.lastSeenAt)}`));
        row.append(copy);
        if (device.current) {
          row.append(element("span", "paired-device-current", "เครื่องนี้"));
        } else {
          const revoke = element("button", "", "ถอนสิทธิ์");
          revoke.type = "button";
          revoke.addEventListener("click", async () => {
            if (!window.confirm(`ถอนสิทธิ์ ${device.name}?`)) return;
            try {
              await syncFetch(`/v1/sync/devices/${encodeURIComponent(device.id)}`, { method: "DELETE" });
              await loadPairedDevices();
              toast("ถอนสิทธิ์อุปกรณ์แล้วครับ");
            } catch (error) { toast(error.message, true); }
          });
          row.append(revoke);
        }
        list.append(row);
      }
    } catch (error) {
      list.replaceChildren(element("p", "dialog-copy", error.message));
    }
  }

  async function createDevicePairing() {
    const button = $("#create-pairing");
    button.disabled = true;
    try {
      const pairing = await syncFetch("/v1/sync/pairings", { method: "POST" });
      state.sync.pairingUrl = pairing.pairUrl;
      $("#pairing-code").textContent = `${pairing.code.slice(0, 4)}-${pairing.code.slice(4)}`;
      $("#pairing-qr").src = `${pairing.qrUrl}?v=${Date.now()}`;
      $("#pairing-expiry").textContent = `หมดอายุ ${relativeTime(pairing.expiresAt)}`;
      $("#pairing-card").classList.remove("hidden");
    } catch (error) {
      toast(error.message, true);
    } finally {
      button.disabled = !state.sync.paired;
    }
  }

  function conversationTitle(text) {
    const clean = String(text || "แชตใหม่").replace(/\s+/g, " ").trim();
    return clean.length > 42 ? `${clean.slice(0, 42)}…` : clean || "แชตใหม่";
  }

  function saveConversation(seed = "", conversationId = state.currentConversationId, messages = state.chatMessages) {
    let current = state.conversations.find((item) => item.id === conversationId);
    if (!current) {
      current = {
        id: conversationId, title: conversationTitle(seed), updatedAt: Date.now(), messages: [],
        pinned: false, archived: false
      };
      state.conversations.unshift(current);
    }
    if (current.title === "แชตใหม่" && seed) current.title = conversationTitle(seed);
    current.updatedAt = Date.now();
    current.messages = compactMessages(messages);
    state.conversations.sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt);
    persistConversations();
    renderConversationList();
    scheduleConversationSync(conversationId);
  }

  function renderConversationList() {
    const query = state.conversationQuery.trim().toLocaleLowerCase("th");
    const visible = state.conversations.filter((conversation) => {
      if (Boolean(conversation.archived) !== state.showArchived) return false;
      if (!query) return true;
      return `${conversation.title}\n${(conversation.messages || []).map((message) => message.content).join("\n")}`
        .toLocaleLowerCase("th").includes(query);
    });
    for (const list of document.querySelectorAll("[data-conversation-list]")) {
      list.replaceChildren();
      for (const conversation of visible) {
        const item = element("li");
        const button = element("button", conversation.id === state.currentConversationId ? "active" : "");
        const pending = state.pendingChats.has(conversation.id);
        button.classList.toggle("pending", pending);
        button.type = "button";
        button.title = pending ? `${conversation.title} — มินิคุงกำลังตอบ` : conversation.title;
        button.append(element("span", "", `${conversation.pinned ? "◆ " : ""}${conversation.title}`));
        button.addEventListener("click", () => {
          mobileMenuDialog?.close();
          switchConversation(conversation.id);
        });
        const actions = element("button", "conversation-actions-button", "•••");
        actions.type = "button";
        actions.setAttribute("aria-label", `จัดการ ${conversation.title}`);
        actions.addEventListener("click", () => openConversationActions(conversation.id));
        item.append(button, actions);
        list.append(item);
      }
    }
    document.querySelectorAll("[data-conversation-empty]").forEach((node) => {
      node.classList.toggle("hidden", visible.length > 0);
      node.textContent = query ? "ไม่พบบทสนทนาที่ค้นหา" : state.showArchived
        ? "ยังไม่มีบทสนทนาที่เก็บถาวร" : "บทสนทนาจะซิงก์ข้ามอุปกรณ์ครับ";
    });
  }

  function startNewChat() {
    state.currentConversationId = chatId();
    state.chatMessages = [];
    state.attachments = [];
    renderAttachmentTray();
    renderChat();
    renderConversationList();
    syncChatState();
    showView("chat");
    restoreDraft();
    $("#chat-composer").focus();
  }

  function switchConversation(id) {
    const conversation = state.conversations.find((item) => item.id === id);
    if (!conversation) return;
    state.currentConversationId = id;
    state.chatMessages = Array.isArray(conversation.messages) ? conversation.messages : [];
    state.attachments = [];
    renderAttachmentTray();
    renderChat();
    renderConversationList();
    syncChatState();
    showView("chat");
    restoreDraft();
  }

  function draftKey() {
    return `minikun.draft.${state.currentConversationId}`;
  }

  function saveDraft() {
    try { localStorage.setItem(draftKey(), $("#chat-composer").value); } catch (_) { /* quota */ }
  }

  function restoreDraft() {
    const composer = $("#chat-composer");
    try { composer.value = localStorage.getItem(draftKey()) || ""; } catch (_) { composer.value = ""; }
    autoGrowComposer();
  }

  function actionConversation() {
    return state.conversations.find((conversation) => conversation.id === state.actionConversationId);
  }

  function openConversationActions(id) {
    const conversation = state.conversations.find((item) => item.id === id);
    if (!conversation) return;
    state.actionConversationId = id;
    $("#conversation-title-input").value = conversation.title;
    $("#pin-conversation").textContent = conversation.pinned ? "เลิกปักหมุด" : "ปักหมุด";
    $("#archive-conversation").textContent = conversation.archived ? "นำกลับจากคลัง" : "เก็บเข้าคลัง";
    if (!$("#conversation-actions-dialog").open) $("#conversation-actions-dialog").showModal();
  }

  function touchConversation(conversation) {
    conversation.updatedAt = Date.now();
    state.conversations.sort((left, right) => Number(right.pinned) - Number(left.pinned)
      || right.updatedAt - left.updatedAt);
    persistConversations();
    renderConversationList();
    scheduleConversationSync(conversation.id);
  }

  function exportConversation(conversation) {
    const markdownText = [
      `# ${conversation.title}`,
      "",
      ...(conversation.messages || []).flatMap((message) => [
        `## ${message.role === "assistant" ? "Minikun" : "User"}`,
        "",
        message.content || "",
        ...(message.sources?.length ? ["", "Sources:", ...message.sources.map((source) => `- ${source.url}`)] : []),
        ""
      ])
    ].join("\n");
    const link = document.createElement("a");
    link.href = URL.createObjectURL(new Blob([markdownText], { type: "text/markdown;charset=utf-8" }));
    link.download = `${conversation.title.replace(/[^\p{L}\p{N}._-]+/gu, "-").slice(0, 80) || "minikun-chat"}.md`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
  }

  function exportConversationJson(conversation) {
    const link = document.createElement("a");
    link.href = URL.createObjectURL(new Blob([JSON.stringify(syncPayload(conversation), null, 2)],
      { type: "application/json;charset=utf-8" }));
    link.download = `${conversation.title.replace(/[^\p{L}\p{N}._-]+/gu, "-").slice(0, 80) || "minikun-chat"}.json`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
  }

  async function deleteConversation(conversation) {
    if (!conversation || !window.confirm(`ลบ “${conversation.title}” และบริบทระยะสั้นของแชตนี้?`)) return;
    await api(`/v1/conversations/${encodeURIComponent(conversation.id)}`, { method: "DELETE" });
    if (state.sync.paired) {
      await syncFetch(`/v1/sync/conversations/${encodeURIComponent(conversation.id)}`, { method: "DELETE" });
    }
    state.conversations = state.conversations.filter((item) => item.id !== conversation.id);
    persistConversations();
    $("#conversation-actions-dialog").close();
    if (state.currentConversationId === conversation.id) {
      const next = state.conversations.find((item) => !item.archived);
      if (next) switchConversation(next.id); else startNewChat();
    } else {
      renderConversationList();
    }
    toast("ลบบทสนทนาและ short-term memory แล้วครับ");
  }

  function showView(view, section = "") {
    document.querySelectorAll("[data-view]").forEach((node) => node.classList.toggle("hidden", node.dataset.view !== view));
    document.querySelectorAll("[data-view-target]").forEach((node) => {
      const matchesView = node.dataset.viewTarget === view;
      const matchesSection = section ? node.dataset.section === section : !node.dataset.section;
      node.classList.toggle("active", matchesView && matchesSection);
    });
    if (view === "cockpit") {
      const page = { experiments: "experiments", inbox: "work", "permission-center": "permissions" }[section]
        || state.cockpitPage || "overview";
      switchCockpitPage(page, section);
      loadDashboard();
    }
  }

  function switchCockpitPage(page = "overview", focusId = "") {
    const allowed = new Set(["overview", "work", "tools", "health", "memory", "permissions", "experiments"]);
    state.cockpitPage = allowed.has(page) ? page : "overview";
    document.querySelectorAll("[data-cockpit-page]").forEach((node) => {
      const pages = String(node.dataset.cockpitPage || "").split(/\s+/);
      node.classList.toggle("cockpit-page-hidden", !pages.includes(state.cockpitPage));
    });
    document.querySelectorAll("[data-cockpit-target]").forEach((button) => {
      const active = button.dataset.cockpitTarget === state.cockpitPage;
      button.classList.toggle("active", active);
      if (active) button.setAttribute("aria-current", "page");
      else button.removeAttribute("aria-current");
    });
    requestAnimationFrame(() => {
      if (focusId) document.getElementById(focusId)?.scrollIntoView({ behavior: "smooth", block: "start" });
      else window.scrollTo(0, 0);
    });
  }

  function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, (character) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#039;"
    })[character]);
  }

  function markdown(value) {
    const source = String(value || "");
    const blocks = [];
    const tokenized = source.replace(/```([\w.+-]*)\n?([\s\S]*?)```/g, (_, language, code) => {
      const token = `@@CODE_BLOCK_${blocks.length}@@`;
      blocks.push(`<div class="code-block"><header><span>${escapeHtml(language || "code")}</span><button type="button" data-copy-code>คัดลอก</button></header><pre><code>${escapeHtml(code.replace(/\n$/, ""))}</code></pre></div>`);
      return token;
    });
    let html = escapeHtml(tokenized)
      .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
      .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/`([^`\n]+)`/g, "<code>$1</code>");
    html = html.split(/\n{2,}/).map((part) => {
      if (/^@@CODE_BLOCK_\d+@@$/.test(part.trim())) return part.trim();
      const lines = part.split("\n");
      if (lines.every((line) => /^\s*[-*]\s+/.test(line))) {
        return `<ul>${lines.map((line) => `<li>${line.replace(/^\s*[-*]\s+/, "")}</li>`).join("")}</ul>`;
      }
      if (lines.every((line) => /^\s*\d+[.)]\s+/.test(line))) {
        return `<ol>${lines.map((line) => `<li>${line.replace(/^\s*\d+[.)]\s+/, "")}</li>`).join("")}</ol>`;
      }
      return `<p>${lines.join("<br>")}</p>`;
    }).join("");
    return blocks.reduce((result, block, index) => result.replace(`@@CODE_BLOCK_${index}@@`, block), html);
  }

  async function copyText(value) {
    const text = String(value ?? "");
    if (navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(text);
        return;
      } catch (_) { /* Safari on an HTTP LAN origin needs the legacy fallback */ }
    }
    const textarea = document.createElement("textarea");
    textarea.value = text;
    textarea.readOnly = true;
    textarea.setAttribute("aria-hidden", "true");
    Object.assign(textarea.style, {
      position: "fixed", top: "0", left: "0", width: "1px", height: "1px",
      padding: "0", border: "0", opacity: "0", fontSize: "16px"
    });
    document.body.append(textarea);
    textarea.focus();
    textarea.select();
    textarea.setSelectionRange(0, textarea.value.length);
    let copied = false;
    try { copied = document.execCommand("copy"); }
    finally { textarea.remove(); }
    if (!copied) throw new Error("คัดลอกไม่สำเร็จ กรุณาแตะข้อความค้างไว้ครับ");
  }

  function formatDuration(milliseconds) {
    const value = Math.max(0, Number(milliseconds) || 0);
    if (value < 1000) return `${Math.max(0.1, value / 1000).toFixed(1)} วิ`;
    if (value < 60_000) return `${(value / 1000).toFixed(value < 10_000 ? 1 : 0)} วิ`;
    const minutes = Math.floor(value / 60_000);
    const seconds = Math.round((value % 60_000) / 1000);
    return `${minutes} นาที ${seconds} วิ`;
  }

  function formatUptime(milliseconds) {
    const hours = Math.floor(Math.max(0, Number(milliseconds) || 0) / 3_600_000);
    if (hours < 24) return `${hours} ชม.`;
    const days = Math.floor(hours / 24);
    return `${days} วัน ${hours % 24} ชม.`;
  }

  function formatBinaryBytes(bytes) {
    const value = Number(bytes);
    if (!Number.isFinite(value) || value < 0) return "—";
    const gibibyte = 1024 ** 3;
    const mebibyte = 1024 ** 2;
    if (value >= gibibyte) return `${(value / gibibyte).toFixed(2)} GiB`;
    if (value >= mebibyte) return `${Math.round(value / mebibyte)} MiB`;
    return `${Math.round(value / 1024)} KiB`;
  }

  function renderResponseMeta(message) {
    if (message.role !== "assistant") return null;
    const values = [];
    const contextTokens = Number(message.usage?.promptTokens);
    if (contextTokens > 0) values.push(`Context ${contextTokens.toLocaleString("th-TH")} tokens`);
    const thinkingMs = Number(message.timing?.firstTokenMs || message.timing?.totalMs);
    if (thinkingMs > 0) values.push(`คิด ${formatDuration(thinkingMs)}`);
    const totalMs = Number(message.timing?.totalMs);
    if (totalMs > thinkingMs) values.push(`รวม ${formatDuration(totalMs)}`);
    if (message.status === "stopped") values.push("หยุดโดยผู้ใช้");
    if (message.status === "failed") values.push("ส่งไม่สำเร็จ");
    if (!values.length) return null;
    const meta = element("div", "response-meta");
    values.forEach((value) => meta.append(element("span", "", value)));
    return meta;
  }

  function renderSourceCards(message) {
    if (message.role !== "assistant" || !message.sources?.length) return null;
    const region = element("section", "message-sources");
    region.append(element("strong", "", "แหล่งอ้างอิง"));
    const list = element("div", "message-source-list");
    for (const source of message.sources) {
      const link = element("a", "message-source", source.title || source.url);
      link.href = source.url;
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      list.append(link);
    }
    region.append(list);
    return region;
  }

  function setMessageFeedback(message, value) {
    if (message.feedback === value) {
      message.feedback = "";
      message.feedbackReason = "";
    } else {
      message.feedback = value;
      message.feedbackReason = value === "down"
        ? String(window.prompt("มินิคุงควรปรับอะไรในคำตอบนี้? (ไม่บังคับ)", "") || "").trim().slice(0, 500)
        : "";
    }
    saveConversation("", state.currentConversationId, state.chatMessages);
    renderChat();
    toast(message.feedback ? "บันทึก feedback แล้วครับ" : "ยกเลิก feedback แล้วครับ");
  }

  function renderLiveProgress(message) {
    const progress = element("div", "live-progress");
    const pulse = element("span", "live-progress-pulse");
    pulse.innerHTML = "<i></i><i></i><i></i>";
    const copy = element("div");
    copy.append(element("strong", "", message.progress || "กำลังทำความเข้าใจคำถาม…"));
    const seconds = Math.max(0, Math.floor((Date.now() - Number(message.timing?.startedAt || Date.now())) / 1000));
    copy.append(element("small", "", seconds ? `รอมา ${seconds} วินาที` : "เริ่มทำงานแล้วครับ"));
    progress.append(pulse, copy);
    return progress;
  }

  function renderMessage(message, live = false) {
    const row = element("article", `message ${message.role}`);
    row.dataset.messageId = message.id || "";
    const avatar = message.role === "assistant"
      ? Object.assign(element("img", "message-avatar"), { src: "/cockpit/minikun-avatar.jpg", alt: "มินิคุง" })
      : element("span", "message-avatar user-avatar", "YOU");
    const body = element("div", "message-body");
    body.append(element("div", "message-role", message.role === "assistant" ? "MINIKUN" : "YOU"));
    if (message.files?.length) {
      const files = element("div", "message-files");
      for (const file of message.files) files.append(element("span", "message-file", file));
      body.append(files);
    }
    const content = element("div", "message-content");
    if (live && !message.content) {
      content.append(renderLiveProgress(message));
    } else {
      content.innerHTML = markdown(message.content);
    }
    body.append(content);
    if (message.attachments?.length) {
      const images = element("div", "message-image-grid");
      for (const attachment of message.attachments) {
        if (!attachment.url) continue;
        const link = element("a");
        link.href = attachment.url;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        const image = element("img");
        image.src = attachment.url;
        image.alt = attachment.title || "ภาพจากมินิคุง";
        link.append(image);
        images.append(link);
      }
      if (images.childElementCount) body.append(images);
    }
    const sourceCards = renderSourceCards(message);
    if (sourceCards) body.append(sourceCards);
    const responseMeta = renderResponseMeta(message);
    if (responseMeta) body.append(responseMeta);
    if (!live) {
      const tools = element("div", "message-tools");
      const copy = element("button", "", "คัดลอก");
      copy.type = "button";
      copy.addEventListener("click", async () => {
        try {
          await copyText(message.content || "");
          copy.textContent = "คัดลอกแล้ว";
          setTimeout(() => { copy.textContent = "คัดลอก"; }, 1400);
        } catch (error) { toast(error.message, true); }
      });
      tools.append(copy);
      const index = state.chatMessages.indexOf(message);
      const branch = element("button", "", "แตกสาขา");
      branch.type = "button";
      branch.addEventListener("click", () => branchFromMessage(index));
      tools.append(branch);
      if (message.role === "user") {
        const edit = element("button", "", "แก้ไข");
        edit.type = "button";
        edit.addEventListener("click", () => editUserMessage(index));
        tools.append(edit);
      } else {
        const regenerate = element("button", "", message.status === "failed" ? "ลองอีกครั้ง" : "ตอบใหม่");
        regenerate.type = "button";
        regenerate.addEventListener("click", () => regenerateMessage(index));
        tools.append(regenerate);
        for (const [value, label] of [["up", "👍"], ["down", "👎"]]) {
          const feedback = element("button", message.feedback === value ? "selected" : "", label);
          feedback.type = "button";
          feedback.setAttribute("aria-label", value === "up" ? "คำตอบมีประโยชน์" : "คำตอบควรปรับปรุง");
          feedback.addEventListener("click", () => setMessageFeedback(message, value));
          tools.append(feedback);
        }
      }
      body.append(tools);
    }
    row.append(avatar, body);
    return row;
  }

  function renderChat() {
    const messages = $("#messages");
    messages.replaceChildren();
    $("#chat-welcome").classList.toggle("hidden", state.chatMessages.length > 0);
    for (const message of state.chatMessages) messages.append(renderMessage(message));
    const pending = state.pendingChats.get(state.currentConversationId);
    if (pending && !state.chatMessages.includes(pending.assistant)) {
      messages.append(renderMessage(pending.assistant, true));
    }
    requestAnimationFrame(scrollToLatest);
  }

  function scrollToLatest() {
    const scroll = $("#messages-scroll");
    scroll.scrollTop = scroll.scrollHeight;
  }

  function setChatBusy(busy, status = "พร้อมช่วยพี่สาว") {
    $("#messages").setAttribute("aria-busy", String(busy));
    $("#send-message").classList.toggle("hidden", busy);
    $("#stop-message").classList.toggle("hidden", !busy);
    $("#chat-status").textContent = status;
  }

  function syncChatState() {
    if (state.chatTranscribing) {
      setChatBusy(true, "กำลังฟังเสียง…");
      return;
    }
    const pending = state.pendingChats.get(state.currentConversationId);
    setChatBusy(Boolean(pending), pending?.status || "พร้อมช่วยพี่สาว");
  }

  function updatePendingProgress(task, label) {
    const changed = task.status !== label;
    task.status = label;
    task.assistant.progress = label;
    if (state.currentConversationId !== task.conversationId) return;
    const row = document.querySelector(`[data-message-id="${task.assistant.id}"]`);
    const labelNode = row?.querySelector(".live-progress strong");
    const timeNode = row?.querySelector(".live-progress small");
    if (labelNode) labelNode.textContent = label;
    if (timeNode) {
      const seconds = Math.max(0, Math.floor((Date.now() - task.assistant.timing.startedAt) / 1000));
      timeNode.textContent = seconds ? `รอมา ${seconds} วินาที` : "เริ่มทำงานแล้วครับ";
    }
    if (changed) syncChatState();
  }

  function startPendingProgress(task, prompt) {
    const researchLikely = /(ค้น|หา(?:ข้อมูล|ข่าว)|ล่าสุด|เว็บ|แหล่งข้อมูล|อ้างอิง|research|search|https?:\/\/)/i.test(prompt);
    const stage = (elapsed) => {
      if (elapsed < 3) return "กำลังทำความเข้าใจคำถาม…";
      if (elapsed < 8) return "กำลังคิดและวางแผนคำตอบ…";
      if (elapsed < 18) return researchLikely ? "กำลังหาข้อมูลที่เกี่ยวข้อง…" : "กำลังเลือกข้อมูลและเครื่องมือ…";
      return "กำลังตรวจและเรียบเรียงคำตอบ…";
    };
    updatePendingProgress(task, stage(0));
    task.progressTimer = window.setInterval(() => {
      const elapsed = (Date.now() - task.assistant.timing.startedAt) / 1000;
      updatePendingProgress(task, stage(elapsed));
    }, 1000);
  }

  function autoGrowComposer() {
    const composer = $("#chat-composer");
    composer.style.height = "auto";
    composer.style.height = `${Math.min(190, composer.scrollHeight)}px`;
  }

  function fileAsDataUrl(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = () => reject(new Error(`อ่าน ${file.name} ไม่สำเร็จ`));
      reader.readAsDataURL(file);
    });
  }

  function fileAsText(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = () => reject(new Error(`อ่าน ${file.name} ไม่สำเร็จ`));
      reader.readAsText(file);
    });
  }

  function attachmentDatabase() {
    return new Promise((resolve, reject) => {
      if (!("indexedDB" in globalThis)) {
        reject(new Error("Browser นี้ไม่รองรับที่เก็บไฟล์สำหรับแชตต่อเนื่อง"));
        return;
      }
      const request = indexedDB.open("minikun-chat-assets", 1);
      request.onupgradeneeded = () => request.result.createObjectStore("assets", { keyPath: "id" });
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error || new Error("เปิดที่เก็บไฟล์ไม่สำเร็จ"));
    });
  }

  async function saveAttachmentAsset(attachment) {
    const asset = {
      id: attachment.assetId || uniqueId("asset-"),
      kind: attachment.kind,
      name: attachment.name,
      type: attachment.type,
      value: attachment.kind === "image" ? attachment.data : attachment.text,
      createdAt: Date.now()
    };
    try {
      const database = await attachmentDatabase();
      await new Promise((resolve, reject) => {
        const transaction = database.transaction("assets", "readwrite");
        transaction.objectStore("assets").put(asset);
        transaction.oncomplete = resolve;
        transaction.onerror = () => reject(transaction.error);
      });
      database.close();
      attachment.assetId = asset.id;
    } catch (error) {
      console.warn("Chat attachment persistence unavailable", error);
    }
    return attachment;
  }

  async function loadAttachmentAsset(assetId) {
    if (!assetId) return null;
    try {
      const database = await attachmentDatabase();
      const result = await new Promise((resolve, reject) => {
        const request = database.transaction("assets", "readonly").objectStore("assets").get(assetId);
        request.onsuccess = () => resolve(request.result || null);
        request.onerror = () => reject(request.error);
      });
      database.close();
      return result;
    } catch (_) {
      return null;
    }
  }

  async function addFiles(files) {
    for (const file of Array.from(files).slice(0, 6)) {
      if (file.size > 10 * 1024 * 1024) {
        toast(`${file.name} ใหญ่เกิน 10 MB ครับ`, true);
        continue;
      }
      try {
        if (file.type.startsWith("image/")) {
          state.attachments.push(await saveAttachmentAsset({
            kind: "image", name: file.name, type: file.type, data: await fileAsDataUrl(file)
          }));
        } else {
          if (file.size > 500 * 1024) {
            toast(`${file.name} ใหญ่เกิน 500 KB สำหรับไฟล์ข้อความครับ`, true);
            continue;
          }
          state.attachments.push(await saveAttachmentAsset({
            kind: "text", name: file.name, type: file.type, text: await fileAsText(file)
          }));
        }
      } catch (error) { toast(error.message, true); }
    }
    renderAttachmentTray();
  }

  function renderAttachmentTray() {
    const tray = $("#attachment-tray");
    tray.replaceChildren();
    tray.classList.toggle("hidden", state.attachments.length === 0);
    state.attachments.forEach((attachment, index) => {
      const chip = element("div", "attachment-chip");
      if (attachment.kind === "image") {
        const image = element("img");
        image.src = attachment.data;
        image.alt = "";
        chip.append(image);
      }
      chip.append(element("span", "", attachment.name));
      const remove = element("button", "", "×");
      remove.type = "button";
      remove.setAttribute("aria-label", `นำ ${attachment.name} ออก`);
      remove.addEventListener("click", () => {
        state.attachments.splice(index, 1);
        renderAttachmentTray();
      });
      chip.append(remove);
      tray.append(chip);
    });
  }

  function requestContent(text, attachments) {
    const documents = attachments.filter((item) => item.kind === "text");
    const images = attachments.filter((item) => item.kind === "image");
    let combined = text;
    for (const document of documents) {
      combined += `\n\n[ไฟล์แนบ: ${document.name}]\n\`\`\`text\n${document.text}\n\`\`\``;
    }
    if (state.currentLocation) {
      const { latitude, longitude, accuracy } = state.currentLocation;
      combined += `\n\n[ตำแหน่งจากอุปกรณ์ขณะนี้: latitude ${latitude}, longitude ${longitude}, accuracy ${accuracy} เมตร]`;
    }
    if (!images.length) return combined;
    return [
      { type: "text", text: combined || "ช่วยดูภาพที่แนบมานี้ให้หน่อยครับ" },
      ...images.map((image) => ({ type: "image_url", image_url: { url: image.data, detail: "auto" } }))
    ];
  }

  async function promptMessages(messages, currentUserMessage, currentContent) {
    const selected = messages
      .filter((message) => message === currentUserMessage || !message.localOnly)
      .filter((message) => message.role === "user" || message.role === "assistant")
      .slice(-40);
    const recentTextAssetIds = new Set();
    const recentImageAssetIds = [];
    for (let messageIndex = selected.length - 1; messageIndex >= 0; messageIndex--) {
      const message = selected[messageIndex];
      if (message === currentUserMessage || message.role !== "user") continue;
      const attachments = message.attachments || [];
      for (let attachmentIndex = attachments.length - 1; attachmentIndex >= 0; attachmentIndex--) {
        const attachment = attachments[attachmentIndex];
        if (!attachment.assetId) continue;
        if (attachment.kind === "text" && recentTextAssetIds.size < 3) {
          recentTextAssetIds.add(attachment.assetId);
        }
        if (attachment.kind === "image" && recentImageAssetIds.length < 2
            && !recentImageAssetIds.includes(attachment.assetId)) {
          recentImageAssetIds.push(attachment.assetId);
        }
      }
      if (recentTextAssetIds.size >= 3 && recentImageAssetIds.length >= 2) break;
    }
    let restoredTextCharacters = 0;
    const result = [];
    for (const message of selected) {
      let content = message === currentUserMessage ? currentContent : String(message.content || "").trim();
      if (message !== currentUserMessage && message.role === "user") {
        for (const attachment of (message.attachments || [])) {
          if (!recentTextAssetIds.has(attachment.assetId)) continue;
          const asset = await loadAttachmentAsset(attachment.assetId);
          if (asset?.kind === "text" && asset.value && restoredTextCharacters < 30000) {
            const excerpt = String(asset.value).slice(0, 30000 - restoredTextCharacters);
            restoredTextCharacters += excerpt.length;
            content += `\n\n[ไฟล์แนบจากข้อความนี้: ${asset.name}]\n\`\`\`text\n${excerpt}\n\`\`\``;
          }
        }
      }
      if (Array.isArray(content) || content) result.push({ role: message.role, content });
    }
    const previousImages = [];
    for (const assetId of recentImageAssetIds.reverse()) {
      const asset = await loadAttachmentAsset(assetId);
      if (asset?.kind === "image" && asset.value) {
        previousImages.push({ name: asset.name, data: asset.value });
      }
    }
    if (previousImages.length && result.length) {
      const current = result.at(-1);
      const parts = Array.isArray(current.content)
        ? [...current.content]
        : [{ type: "text", text: String(current.content || "") }];
      for (const image of previousImages) {
        parts.push({ type: "text", text: `[ภาพจากข้อความก่อนหน้า: ${image.name}]` });
        parts.push({ type: "image_url", image_url: { url: image.data, detail: "auto" } });
      }
      current.content = parts;
    }
    return result;
  }

  async function consumeChatStream(response, assistant, task) {
    if (!response.body) throw new Error("Browser นี้ยังไม่รองรับ streaming response");
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    const update = () => {
      const node = document.querySelector(`[data-message-id="${assistant.id}"] .message-content`);
      if (node && assistant.content) node.innerHTML = markdown(assistant.content);
      scrollToLatest();
    };
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
      const lines = buffer.split(/\r?\n/);
      buffer = done ? "" : lines.pop();
      for (const rawLine of lines) {
        const line = rawLine.replace(/^data:\s?/, "").trim();
        if (!line || line === "[DONE]") continue;
        try {
          const chunk = JSON.parse(line);
          const delta = chunk.choices?.[0]?.delta;
          if (delta?.tool_calls?.length || chunk.tool_calls?.length || chunk.stage === "tool") {
            updatePendingProgress(task, "กำลังใช้เครื่องมือช่วยหาคำตอบ…");
          }
          if (delta?.content) {
            if (!assistant.timing.firstTokenMs) assistant.timing.firstTokenMs = Date.now() - assistant.timing.startedAt;
            assistant.content += delta.content;
            updatePendingProgress(task, "กำลังเรียบเรียงคำตอบ…");
          }
          if (chunk.usage) {
            assistant.usage = {
              promptTokens: Number(chunk.usage.prompt_tokens) || 0,
              completionTokens: Number(chunk.usage.completion_tokens) || 0,
              totalTokens: Number(chunk.usage.total_tokens) || 0
            };
          }
          const images = [...(chunk.attachments || []), ...(delta?.images || [])].map((image) => ({
            title: image.title || "ภาพจากมินิคุง",
            url: image.url || image.image_url?.url
          })).filter((image) => image.url);
          if (images.length) assistant.attachments = [...(assistant.attachments || []), ...images];
          update();
        } catch (_) { /* ignore keep-alive and incomplete event lines */ }
      }
      if (done) break;
    }
  }

  function extractSources(content) {
    const seen = new Set();
    const sources = [];
    for (const match of String(content || "").matchAll(/https?:\/\/[^\s)\]}>"']+/g)) {
      const url = match[0].replace(/[.,;:!?]+$/, "");
      if (seen.has(url)) continue;
      seen.add(url);
      try {
        sources.push({ url, title: new URL(url).hostname.replace(/^www\./, "") });
      } catch (_) { /* ignore malformed model output */ }
      if (sources.length >= 12) break;
    }
    return sources;
  }

  async function hydrateMessageAttachments(message) {
    const hydrated = [];
    for (const attachment of (message.attachments || []).slice(0, 6)) {
      const asset = await loadAttachmentAsset(attachment.assetId);
      if (asset) {
        hydrated.push({
          kind: asset.kind, name: asset.name, type: asset.type, assetId: asset.id,
          ...(asset.kind === "image" ? { data: asset.value } : { text: asset.value })
        });
      }
    }
    return hydrated;
  }

  async function runChatTurn({ conversationId, messages, userMessage, currentContent, seed }) {
    const assistant = {
      id: uniqueId("message-"), role: "assistant", content: "", attachments: [], createdAt: Date.now(),
      parentId: userMessage.id, branchId: userMessage.branchId || conversationId, status: "generating",
      timing: { startedAt: Date.now(), firstTokenMs: 0, totalMs: 0 }, sources: [], feedback: ""
    };
    $("#chat-welcome").classList.add("hidden");
    renderChat();
    $("#messages").append(renderMessage(assistant, true));
    scrollToLatest();
    saveConversation(seed || userMessage.content, conversationId, messages);
    const task = {
      controller: new AbortController(), assistant, conversationId,
      status: "กำลังทำความเข้าใจคำถาม…", progressTimer: null
    };
    state.pendingChats.set(conversationId, task);
    startPendingProgress(task, userMessage.content);
    renderConversationList();
    syncChatState();
    try {
      const response = await fetch("/v1/chat/completions", {
        method: "POST",
        headers: { ...headers(true), "X-Conversation-Id": conversationId },
        signal: task.controller.signal,
        body: JSON.stringify({
          model: state.model,
          messages: await promptMessages(messages, userMessage, currentContent),
          conversation_id: conversationId,
          owner_id: state.ownerId,
          stream: true
        })
      });
      if (!response.ok) {
        let message = `มินิคุงตอบไม่ได้ (${response.status})`;
        try { const body = await response.json(); message = body.error?.message || body.message || message; } catch (_) { /* noop */ }
        throw new Error(message);
      }
      await consumeChatStream(response, assistant, task);
      assistant.timing.totalMs = Date.now() - assistant.timing.startedAt;
      if (!assistant.content && !assistant.attachments.length) assistant.content = "ได้รับข้อความแล้วครับ แต่ยังไม่มีคำตอบกลับมา";
      assistant.status = "complete";
      assistant.sources = extractSources(assistant.content);
      messages.push(assistant);
      saveConversation(seed || userMessage.content, conversationId, messages);
      if (state.currentConversationId === conversationId) {
        renderChat();
        if (state.voiceOutput && assistant.content) speak(assistant.content);
      }
    } catch (error) {
      if (error.name === "AbortError") {
        assistant.timing.totalMs = Date.now() - assistant.timing.startedAt;
        if (assistant.content) {
          assistant.content += "\n\n_หยุดคำตอบแล้ว_";
          assistant.status = "stopped";
          messages.push(assistant);
          saveConversation(seed || userMessage.content, conversationId, messages);
        }
        if (state.currentConversationId === conversationId) renderChat();
      } else {
        assistant.timing.totalMs = Date.now() - assistant.timing.startedAt;
        assistant.content = `ขออภัยครับ ตอนนี้เชื่อมต่อไม่สำเร็จ\n\n${error.message}`;
        assistant.localOnly = true;
        assistant.status = "failed";
        messages.push(assistant);
        saveConversation(seed || userMessage.content, conversationId, messages);
        if (state.currentConversationId === conversationId) renderChat();
        toast(error.message, true);
      }
    } finally {
      window.clearInterval(task.progressTimer);
      if (state.pendingChats.get(conversationId) === task) state.pendingChats.delete(conversationId);
      renderConversationList();
      syncChatState();
      if (!state.pendingChats.size && state.sync.remoteDirty) refreshSyncedConversations();
      if (state.currentConversationId === conversationId && !window.matchMedia("(pointer: coarse)").matches) {
        $("#chat-composer").focus();
      }
    }
  }

  async function sendChat(event) {
    event?.preventDefault();
    const conversationId = state.currentConversationId;
    if (state.chatTranscribing || state.pendingChats.has(conversationId)) return;
    const composer = $("#chat-composer");
    const text = composer.value.trim();
    const attachments = state.attachments.splice(0);
    if (!text && !attachments.length) return;
    const files = attachments.map((item) => item.name);
    const messages = state.chatMessages;
    const userMessage = {
      id: uniqueId("message-"), role: "user", content: text || "ช่วยดูไฟล์ที่แนบมานี้ให้หน่อยครับ", files,
      attachments: attachments.map((item) => ({
        title: item.name, url: item.kind === "image" ? item.data : "", assetId: item.assetId,
        kind: item.kind, type: item.type
      })),
      branchId: conversationId, status: "complete", createdAt: Date.now()
    };
    messages.push(userMessage);
    composer.value = "";
    try { localStorage.removeItem(draftKey()); } catch (_) { /* noop */ }
    autoGrowComposer();
    renderAttachmentTray();
    await runChatTurn({
      conversationId, messages, userMessage,
      currentContent: requestContent(text, attachments), seed: text || files[0]
    });
  }

  async function rerunFromUser(userIndex, branch = false, editedContent = null) {
    const source = state.chatMessages;
    const original = source[userIndex];
    if (!original || original.role !== "user") return;
    const conversationId = branch ? chatId() : state.currentConversationId;
    const messages = source.slice(0, userIndex + 1).map((message) => ({
      ...message,
      attachments: [...(message.attachments || [])],
      sources: [...(message.sources || [])]
    }));
    let userMessage = messages.at(-1);
    if (editedContent !== null) {
      userMessage = {
        ...userMessage,
        id: uniqueId("message-"), parentId: original.id, branchId: conversationId,
        content: editedContent, createdAt: Date.now()
      };
      messages[messages.length - 1] = userMessage;
    } else {
      userMessage.branchId = conversationId;
    }
    if (branch) {
      state.currentConversationId = conversationId;
      state.chatMessages = messages;
      saveConversation(`${userMessage.content} (สาขา)`, conversationId, messages);
    } else {
      source.splice(userIndex + 1);
      state.chatMessages = source;
    }
    renderChat();
    renderConversationList();
    const attachments = await hydrateMessageAttachments(userMessage);
    await runChatTurn({
      conversationId, messages: state.chatMessages, userMessage,
      currentContent: requestContent(userMessage.content, attachments), seed: userMessage.content
    });
  }

  function userBefore(index) {
    for (let cursor = index - 1; cursor >= 0; cursor--) {
      if (state.chatMessages[cursor].role === "user") return cursor;
    }
    return -1;
  }

  async function regenerateMessage(index) {
    const userIndex = userBefore(index);
    if (userIndex < 0) return;
    const hasLaterTurns = state.chatMessages.slice(index + 1).some((message) => !message.localOnly);
    await rerunFromUser(userIndex, hasLaterTurns);
  }

  async function editUserMessage(index) {
    const message = state.chatMessages[index];
    const edited = window.prompt("แก้ข้อความและสร้างสาขาใหม่", message?.content || "");
    if (edited === null || !edited.trim() || edited.trim() === message.content.trim()) return;
    await rerunFromUser(index, true, edited.trim());
  }

  function branchFromMessage(index) {
    const conversationId = chatId();
    const messages = state.chatMessages.slice(0, index + 1).map((message) => ({
      ...message, branchId: conversationId,
      attachments: [...(message.attachments || [])], sources: [...(message.sources || [])]
    }));
    state.currentConversationId = conversationId;
    state.chatMessages = messages;
    saveConversation(`${messages.find((message) => message.role === "user")?.content || "บทสนทนา"} (สาขา)`,
      conversationId, messages);
    renderChat();
    renderConversationList();
    toast("สร้างสาขาบทสนทนาแล้วครับ");
  }

  function speechText(value) {
    return String(value || "")
      .replace(/```[\s\S]*?```/g, " ข้ามส่วนโค้ด ")
      .replace(/`([^`]+)`/g, "$1")
      .replace(/!\[[^\]]*\]\([^)]*\)/g, "")
      .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
      .replace(/https?:\/\/\S+/g, " ลิงก์ ")
      .replace(/[*_>#|~-]+/g, " ")
      .replace(/\s+/g, " ")
      .trim();
  }

  function isAppleMobile() {
    return /iPad|iPhone|iPod/.test(navigator.userAgent)
      || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  }

  function stopSpeech() {
    if ("speechSynthesis" in window) window.speechSynthesis.cancel();
    if (state.activeAudio) {
      state.activeAudio.pause();
      state.activeAudio.removeAttribute("src");
      state.activeAudio.load();
      state.activeAudio = null;
    }
  }

  function speakWithBrowser(text) {
    if (!("speechSynthesis" in window) || !("SpeechSynthesisUtterance" in window)) {
      return Promise.reject(new Error("อุปกรณ์นี้ไม่มีเสียงอ่านใน browser"));
    }
    return new Promise((resolve, reject) => {
      window.speechSynthesis.cancel();
      const utterance = new SpeechSynthesisUtterance(text.slice(0, 4000));
      const voices = window.speechSynthesis.getVoices();
      utterance.voice = voices.find((voice) => /^th(-|_)/i.test(voice.lang)) || null;
      utterance.lang = "th-TH";
      utterance.rate = 0.96;
      utterance.onend = resolve;
      utterance.onerror = (event) => reject(new Error(event.error === "canceled" ? "ยกเลิกเสียงแล้ว" : "เสียงระบบของ iPhone เล่นไม่สำเร็จ"));
      window.speechSynthesis.speak(utterance);
    });
  }

  async function speakWithServer(text) {
    const response = await fetch("/v1/audio/speech", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ model: "minikun-voice", input: text.slice(0, 1800), voice: "minikun", response_format: "wav", speed: 1 })
    });
    if (!response.ok) {
      let message = `ระบบเสียงยังไม่พร้อม (${response.status})`;
      try { message = (await response.json()).message || message; } catch (_) { /* keep status */ }
      throw new Error(message);
    }
    const url = URL.createObjectURL(await response.blob());
    const audio = new Audio();
    audio.preload = "auto";
    audio.playsInline = true;
    audio.src = url;
    state.activeAudio = audio;
    const release = () => {
      URL.revokeObjectURL(url);
      if (state.activeAudio === audio) state.activeAudio = null;
    };
    audio.addEventListener("ended", release, { once: true });
    audio.addEventListener("error", release, { once: true });
    await audio.play();
  }

  async function speak(value) {
    const text = speechText(value);
    if (!text) return;
    stopSpeech();
    try {
      if (isAppleMobile()) await speakWithBrowser(text);
      else await speakWithServer(text);
    } catch (firstError) {
      try {
        if (isAppleMobile()) await speakWithServer(text);
        else await speakWithBrowser(text);
      } catch (_) {
        toast(firstError.message, true);
      }
    }
  }

  function wavBlob(chunks, sourceRate) {
    const sourceLength = chunks.reduce((total, chunk) => total + chunk.length, 0);
    const source = new Float32Array(sourceLength);
    let sourceOffset = 0;
    for (const chunk of chunks) { source.set(chunk, sourceOffset); sourceOffset += chunk.length; }
    const targetRate = Math.min(16000, sourceRate);
    const ratio = sourceRate / targetRate;
    const sampleCount = Math.floor(source.length / ratio);
    const samples = new Float32Array(sampleCount);
    for (let index = 0; index < sampleCount; index++) {
      const start = Math.floor(index * ratio);
      const end = Math.min(source.length, Math.floor((index + 1) * ratio));
      let total = 0;
      for (let cursor = start; cursor < end; cursor++) total += source[cursor];
      samples[index] = total / Math.max(1, end - start);
    }
    const buffer = new ArrayBuffer(44 + samples.length * 2);
    const view = new DataView(buffer);
    const ascii = (offset, text) => { for (let index = 0; index < text.length; index++) view.setUint8(offset + index, text.charCodeAt(index)); };
    ascii(0, "RIFF"); view.setUint32(4, 36 + samples.length * 2, true); ascii(8, "WAVE"); ascii(12, "fmt ");
    view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
    view.setUint32(24, targetRate, true); view.setUint32(28, targetRate * 2, true); view.setUint16(32, 2, true); view.setUint16(34, 16, true);
    ascii(36, "data"); view.setUint32(40, samples.length * 2, true);
    samples.forEach((sample, index) => {
      const normalized = Math.max(-1, Math.min(1, sample));
      view.setInt16(44 + index * 2, normalized < 0 ? normalized * 0x8000 : normalized * 0x7fff, true);
    });
    return new Blob([buffer], { type: "audio/wav" });
  }

  async function stopRecording() {
    clearInterval(state.recordingTimer);
    $("#record-voice").classList.remove("recording");
    $("#recording-time").classList.add("hidden");
    state.audioProcessor?.disconnect();
    state.audioSource?.disconnect();
    state.mediaStream?.getTracks().forEach((track) => track.stop());
    const context = state.audioContext;
    const chunks = state.recordingChunks;
    state.audioProcessor = null;
    state.audioSource = null;
    state.mediaStream = null;
    state.audioContext = null;
    state.recordingChunks = [];
    if (context) await context.close();
    if (chunks.length) await transcribeAudio(wavBlob(chunks, context?.sampleRate || 16000));
  }

  async function toggleRecording() {
    if (state.audioContext) {
      await stopRecording();
      return;
    }
    if (!permissionSecureContext()) {
      showPermissionHelp("microphone", true);
      return;
    }
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!navigator.mediaDevices?.getUserMedia || !AudioContextClass) {
      toast("Browser นี้ยังไม่รองรับการบันทึกเสียงครับ", true);
      return;
    }
    try {
      state.mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true });
      state.audioContext = new AudioContextClass();
      await state.audioContext.resume();
      state.audioSource = state.audioContext.createMediaStreamSource(state.mediaStream);
      state.audioProcessor = state.audioContext.createScriptProcessor(4096, 1, 1);
      state.recordingChunks = [];
      state.audioProcessor.onaudioprocess = (event) => {
        state.recordingChunks.push(new Float32Array(event.inputBuffer.getChannelData(0)));
        event.outputBuffer.getChannelData(0).fill(0);
      };
      state.audioSource.connect(state.audioProcessor);
      state.audioProcessor.connect(state.audioContext.destination);
      state.recordingStartedAt = Date.now();
      $("#record-voice").classList.add("recording");
      $("#recording-time").classList.remove("hidden");
      state.recordingTimer = setInterval(() => {
        const elapsed = Math.floor((Date.now() - state.recordingStartedAt) / 1000);
        $("#recording-time").textContent = `${String(Math.floor(elapsed / 60)).padStart(2, "0")}:${String(elapsed % 60).padStart(2, "0")}`;
        if (elapsed >= 90) stopRecording();
      }, 500);
    } catch (_) {
      state.mediaStream?.getTracks().forEach((track) => track.stop());
      state.mediaStream = null;
      state.audioContext?.close();
      state.audioContext = null;
      showPermissionHelp("microphone");
    }
  }

  async function transcribeAudio(blob) {
    state.chatTranscribing = true;
    syncChatState();
    try {
      const form = new FormData();
      form.append("file", new File([blob], "minikun-recording.wav", { type: "audio/wav" }));
      form.append("language", "auto");
      const response = await fetch("/v1/audio/transcriptions", { method: "POST", body: form });
      if (!response.ok) throw new Error("ถอดเสียงไม่สำเร็จ");
      const result = await response.json();
      const composer = $("#chat-composer");
      composer.value = `${composer.value}${composer.value ? " " : ""}${result.text || ""}`;
      autoGrowComposer();
      composer.focus();
    } catch (error) { toast(error.message, true); }
    finally {
      state.chatTranscribing = false;
      syncChatState();
    }
  }

  function setSync(ok, label) {
    $("#sync-state").lastChild.textContent = ` ${label}`;
    $("#sync-state").querySelector(".status-dot").style.background = ok ? "#78d29a" : "#e9785d";
  }

  function renderStatus(status = {}, decisionCount = 0) {
    $("#due-count").textContent = status.due_tasks ?? "—";
    $("#goal-count").textContent = status.open_goals ?? "—";
    $("#waiting-count").textContent = (status.agent_waiting_confirmation || 0) + decisionCount;
    $("#attention-copy").textContent = status.attention_required
      ? "มีบางเรื่องที่ถึงเวลาแล้ว มินิคุงเรียงก้าวที่เล็กที่สุดไว้ให้ด้านล่างครับ"
      : "ไม่มีเรื่องเร่งด่วนครับ เลือกหนึ่งก้าวที่อยากขยับอย่างสบาย ๆ ได้เลย";
  }

  function renderActions(actions = []) {
    const list = $("#action-list");
    list.replaceChildren();
    $("#action-empty").classList.toggle("hidden", actions.length > 0);
    for (const action of actions.slice(0, 3)) {
      const row = element("div", "action-item");
      row.append(element("span", "action-priority", String(action.priority)));
      const copy = element("div");
      copy.append(element("strong", "", action.action));
      copy.append(element("small", "", `${action.goalTitle} · ${action.reason}`));
      row.append(copy);
      if (action.taskId) {
        const button = element("button", "", "เสร็จแล้ว");
        button.type = "button";
        button.addEventListener("click", () => completeTask(action.taskId));
        row.append(button);
      }
      list.append(row);
    }
  }

  function renderExperiment(items = []) {
    const active = items.find((item) => ["RUNNING", "PAUSED"].includes(item.experiment.status))
      || items.find((item) => item.experiment.status === "DRAFT") || items[0];
    const card = $("#experiment-card");
    card.classList.toggle("hidden", !active);
    $("#experiment-empty").classList.toggle("hidden", Boolean(active));
    if (!active) return;

    card.replaceChildren();
    const orbit = element("div", "experiment-orbit");
    orbit.style.setProperty("--progress", `${Math.min(100, active.analysis.progressPercent) * 3.6}deg`);
    orbit.append(element("span", "", `${active.analysis.progressPercent}%`));

    const info = element("div", "experiment-info");
    const statusLabels = { DRAFT: "ฉบับร่าง", RUNNING: "กำลังทดลอง", PAUSED: "พักไว้", COMPLETED: "สรุปแล้ว", ABANDONED: "หยุดแล้ว" };
    info.append(element("span", "experiment-status", statusLabels[active.experiment.status] || active.experiment.status));
    info.append(element("h3", "", active.experiment.title));
    info.append(element("p", "", active.experiment.hypothesis));

    const metrics = element("div", "metric-row");
    const current = element("span");
    current.append(element("strong", "", String(active.analysis.currentValue)), ` ${active.experiment.metricUnit}`);
    const checks = element("span");
    checks.append(element("strong", "", String(active.analysis.checkInCount)), " เช็กอิน");
    const days = element("span");
    days.append(element("strong", "", String(active.analysis.remainingDays)), " วันเหลือ");
    metrics.append(current, checks, days);
    if (active.outcomeScore) {
      const score = element("span");
      score.append(element("strong", "", `${active.outcomeScore}/5`), " คะแนนผลลัพธ์");
      metrics.append(score);
    }
    info.append(metrics);

    const controls = element("div", "experiment-controls");
    const addControl = (label, style, handler) => {
      const button = element("button", style, label);
      button.type = "button";
      button.addEventListener("click", handler);
      controls.append(button);
    };
    const id = active.experiment.id;
    switch (active.experiment.status) {
      case "DRAFT":
        addControl("เริ่มทดลอง", "primary-button", () => transitionExperiment(id, "START"));
        addControl("ไม่ทำแล้ว", "secondary-button", () => openExperimentAction(active, "ABANDON"));
        break;
      case "RUNNING":
        addControl("เช็กอิน", "primary-button", () => openExperimentAction(active, "CHECK_IN"));
        addControl("พักไว้", "secondary-button", () => transitionExperiment(id, "PAUSE"));
        addControl("สรุปผล", "secondary-button", () => openExperimentAction(active, "COMPLETE"));
        break;
      case "PAUSED":
        addControl("ทำต่อ", "primary-button", () => transitionExperiment(id, "RESUME"));
        addControl("สรุปผล", "secondary-button", () => openExperimentAction(active, "COMPLETE"));
        addControl("หยุด", "secondary-button", () => openExperimentAction(active, "ABANDON"));
        break;
      case "COMPLETED":
        if (active.outcomeStatus !== "EVALUATED") {
          addControl("ให้คะแนนผลลัพธ์", "primary-button", () => openExperimentAction(active, "EVALUATE"));
        }
        break;
      default: break;
    }
    info.append(controls);
    card.append(orbit, info);
  }

  function renderInbox(items = []) {
    const pending = items.filter((item) => item.status === "PREVIEW");
    $("#inbox-count").textContent = pending.length;
    $("#inbox-empty").classList.toggle("hidden", pending.length > 0);
    const list = $("#inbox-list");
    list.replaceChildren();
    for (const item of pending.slice(0, 3)) {
      const row = element("div", "inbox-item");
      const copy = element("div");
      copy.append(element("strong", "", item.content), element("span", "", item.classification));
      const controls = element("div", "item-controls");
      const save = element("button", "", "ยืนยันเก็บ");
      save.type = "button";
      save.addEventListener("click", () => decideInbox(item.id, true));
      const dismiss = element("button", "", "ไม่เก็บ");
      dismiss.type = "button";
      dismiss.addEventListener("click", () => decideInbox(item.id, false));
      controls.append(save, dismiss);
      row.append(copy, controls);
      list.append(row);
    }
  }

  function renderDecisions(items = []) {
    const pending = items.filter((item) => item.status === "WAITING_CONFIRMATION");
    $("#pending-panel").classList.toggle("hidden", pending.length === 0);
    $("#decision-count").textContent = pending.length;
    const list = $("#decision-list");
    list.replaceChildren();
    for (const item of pending) {
      const row = element("div", "decision-item");
      const copy = element("div");
      copy.append(element("strong", "", item.actionPreview?.title || item.actionPreview?.message || "รายการอัตโนมัติ"));
      copy.append(element("small", "", "มินิคุงเตรียมไว้แล้ว แต่ยังไม่ได้ลงมือครับ"));
      const controls = element("div", "decision-controls");
      const approve = element("button", "", "อนุมัติ");
      approve.type = "button";
      approve.addEventListener("click", () => decideAutomation(item.id, true));
      const reject = element("button", "", "ปฏิเสธ");
      reject.type = "button";
      reject.addEventListener("click", () => decideAutomation(item.id, false));
      controls.append(approve, reject);
      row.append(copy, controls);
      list.append(row);
    }
    return pending.length;
  }

  function renderTimeline(items = []) {
    const list = $("#timeline-list");
    list.replaceChildren();
    $("#timeline-empty").classList.toggle("hidden", items.length > 0);
    for (const item of items.slice(0, state.timelineLimit)) {
      const row = element("li", "timeline-event");
      const copy = element("div");
      copy.append(element("strong", "", item.title));
      if (item.summary) copy.append(element("p", "", item.summary));
      const time = element("time", "", relativeTime(item.occurredAt));
      time.dateTime = item.occurredAt;
      row.append(copy, time);
      list.append(row);
    }
  }

  function renderAgentRuns(items = []) {
    const statusLabels = {
      PLANNED: "วางแผนแล้ว", RUNNING: "กำลังทำ", WAITING_CONFIRMATION: "รอยืนยัน",
      COMPLETED: "เสร็จแล้ว", COMPLETED_WITH_ERRORS: "เสร็จบางส่วน", FAILED: "มีปัญหา", LIMIT_REACHED: "ถึงขีดจำกัด"
    };
    const activeStatuses = new Set(["PLANNED", "RUNNING", "WAITING_CONFIRMATION"]);
    const sorted = [...items].sort((left, right) => {
      const activeDifference = Number(activeStatuses.has(right.status)) - Number(activeStatuses.has(left.status));
      return activeDifference || new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime();
    });
    const visible = sorted.slice(0, 5);
    const activeCount = items.filter((item) => activeStatuses.has(item.status)).length;
    $("#agent-run-count").textContent = activeCount ? `${activeCount} กำลังดูแล` : String(items.length);
    $("#agent-run-empty").classList.toggle("hidden", visible.length > 0);
    const list = $("#agent-run-list");
    list.replaceChildren();
    for (const run of visible) {
      const row = element("article", `agent-run status-${String(run.status || "").toLowerCase()}`);
      const heading = element("div", "agent-run-heading");
      heading.append(element("strong", "", run.objective || "งานของมินิคุง"));
      heading.append(element("span", "run-status", statusLabels[run.status] || run.status));
      const progress = Math.max(0, Math.min(100, Math.round((Number(run.currentStep) / Math.max(1, Number(run.maxSteps))) * 100)));
      const detail = element("div", "agent-run-detail");
      detail.append(element("span", "", `${run.currentStep}/${run.maxSteps} ขั้นตอน`));
      detail.append(element("time", "", relativeTime(run.updatedAt)));
      const track = element("div", "agent-progress");
      const bar = element("span");
      bar.style.width = `${run.status === "COMPLETED" ? 100 : progress}%`;
      track.append(bar);
      row.append(heading, track, detail);
      if (run.summary) row.append(element("p", "agent-summary", run.summary));
      if (["FAILED", "COMPLETED_WITH_ERRORS", "LIMIT_REACHED"].includes(run.status)) {
        const retry = element("button", "text-button", "ลองทำต่อ");
        retry.type = "button";
        retry.addEventListener("click", () => resumeAgentRun(run.id, retry));
        row.append(retry);
      }
      list.append(row);
    }
  }

  function renderToolTimeline(details = []) {
    const statusLabels = {
      RUNNING: "กำลังใช้", RETRYING: "กำลังลองใหม่", COMPLETED: "สำเร็จ",
      FAILED: "ไม่สำเร็จ", WAITING_CONFIRMATION: "รอยืนยัน"
    };
    const steps = details.flatMap((detail) => (detail.steps || []).map((step) => ({ run: detail.run, step })))
      .sort((left, right) => new Date(right.step.updatedAt).getTime() - new Date(left.step.updatedAt).getTime())
      .slice(0, 8);
    $("#tool-step-count").textContent = `${steps.length.toLocaleString("th-TH")} ขั้นตอน`;
    $("#tool-step-empty").classList.toggle("hidden", steps.length > 0);
    const list = $("#tool-step-list");
    list.replaceChildren();
    for (const { run, step } of steps) {
      const row = element("li", `tool-step status-${String(step.status || "").toLowerCase()}`);
      row.append(element("span", "tool-step-marker"));
      const copy = element("div", "tool-step-copy");
      const heading = element("div", "tool-step-heading");
      heading.append(element("strong", "", step.toolName));
      heading.append(element("span", "tool-step-status", statusLabels[step.status] || step.status));
      copy.append(heading);
      copy.append(element("p", "", run?.objective || "งานของมินิคุง"));
      const meta = element("div", "tool-step-meta");
      const finishedAt = step.completedAt || step.updatedAt;
      const duration = Math.max(0, new Date(finishedAt).getTime() - new Date(step.startedAt).getTime());
      meta.append(element("span", "", `ใช้เวลา ${formatDuration(duration)}`));
      meta.append(element("time", "", relativeTime(step.updatedAt)));
      copy.append(meta);
      row.append(copy);
      list.append(row);
    }
  }

  async function loadToolTimeline(runs = []) {
    const recent = [...runs]
      .sort((left, right) => new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime())
      .slice(0, 4);
    if (!recent.length) {
      renderToolTimeline([]);
      return;
    }
    const results = await Promise.allSettled(recent.map((run) => api(`/v1/agent/runs/${run.id}`)));
    renderToolTimeline(results.filter((result) => result.status === "fulfilled").map((result) => result.value));
  }

  function renderSystemHealth(report) {
    const available = Boolean(report && typeof report === "object");
    $("#health-empty").classList.toggle("hidden", available);
    const overall = $("#health-overall");
    overall.textContent = !available ? "อ่านไม่ได้" : report.healthy ? "พร้อมใช้งาน" : "ควรตรวจดู";
    overall.dataset.state = !available ? "unknown" : report.healthy ? "up" : "warning";
    const setResource = (name, source = {}) => {
      const percent = Number(source.usage_percent ?? source.used_percent);
      const valid = Number.isFinite(percent) && percent >= 0;
      $(`#health-${name}`).textContent = valid ? `${Math.round(percent)}%` : "—";
      const bar = $(`#health-${name}-bar`);
      bar.style.width = valid ? `${Math.min(100, percent)}%` : "0%";
      bar.dataset.state = source.status === "UP" ? "up" : String(source.status || "unknown").toLowerCase();
    };
    setResource("cpu", report?.cpu);
    setResource("memory", report?.memory);
    setResource("disk", report?.disk);
    $("#health-uptime").textContent = available ? formatUptime(report.jvm?.uptime_ms) : "—";
    $("#health-runtime").textContent = report?.jvm?.java_version ? `Java ${report.jvm.java_version}` : "JVM";

    const nvAllocator = report?.dependencies?.tinygrad?.allocator_memory;
    const allocatorUp = String(nvAllocator?.status || "UNKNOWN").toUpperCase() === "UP";
    const allocatorCard = $("#health-nv-allocator");
    allocatorCard.dataset.state = allocatorUp ? "up" : "unknown";
    $("#health-nv-status").textContent = allocatorUp ? "TinyGrad · NV device" : "ยังอ่านไม่ได้";
    $("#health-nv-current").textContent = allocatorUp ? formatBinaryBytes(nvAllocator.current_bytes) : "—";
    $("#health-nv-peak").textContent = allocatorUp ? formatBinaryBytes(nvAllocator.peak_bytes) : "—";

    const dependencyLabels = {
      application: "Minikun", postgres: "Database", redis: "Redis", tinygrad: "TinyGrad",
      ollama: "Ollama", searxng: "Search", browser: "Browser"
    };
    const dependencies = Object.entries(report?.dependencies || {});
    const list = $("#health-dependency-list");
    list.replaceChildren();
    for (const [name, value] of dependencies) {
      const row = element("div", "health-dependency");
      const status = String(value.status || "UNKNOWN").toUpperCase();
      row.dataset.state = status.toLowerCase();
      row.append(element("span", "health-dot"));
      const copy = element("div");
      copy.append(element("strong", "", dependencyLabels[name] || name));
      copy.append(element("small", "", status === "UP" ? "เชื่อมต่อแล้ว" : "ยังไม่พร้อม"));
      const latency = Number(value.latency_ms);
      row.append(copy, element("span", "health-latency", Number.isFinite(latency) ? `${latency} ms` : "—"));
      list.append(row);
    }
    list.classList.toggle("hidden", !dependencies.length);
  }

  function activateQuickAction(action) {
    switch (action) {
      case "image":
        showView("chat");
        $("#file-input").click();
        break;
      case "idea":
        showView("cockpit", "inbox");
        requestAnimationFrame(() => {
          $("#capture-input")?.focus();
        });
        break;
      case "reminder": {
        showView("chat");
        const composer = $("#chat-composer");
        composer.value = "ช่วยสร้าง Reminder ให้ฉัน: ";
        autoGrowComposer();
        composer.focus();
        break;
      }
      case "location":
        showView("cockpit", "permission-center");
        requestCurrentLocation();
        break;
      case "voice":
        showView("chat");
        toggleRecording();
        break;
      default: break;
    }
  }

  function latestContextTokens() {
    const messages = state.chatMessages.length
      ? state.chatMessages
      : state.conversations.flatMap((conversation) => conversation.messages || []);
    const latest = [...messages].reverse().find((message) => Number(message.usage?.promptTokens) > 0);
    return Number(latest?.usage?.promptTokens) || 0;
  }

  function renderContextMemory(memories = [], knowledge = {}) {
    const categoryLabels = { FACT: "ข้อมูล", PREFERENCE: "ความชอบ", GOAL: "เป้าหมาย", CONSTRAINT: "ข้อจำกัด", RELATIONSHIP: "ความสัมพันธ์", ROUTINE: "กิจวัตร" };
    $("#memory-count").textContent = memories.length >= 30
      ? `${memories.length.toLocaleString("th-TH")} ล่าสุด`
      : `${memories.length.toLocaleString("th-TH")} ความจำ`;
    const tokens = latestContextTokens();
    $("#context-token-count").textContent = tokens ? `${tokens.toLocaleString("th-TH")} tokens` : "ยังไม่มี";
    $("#knowledge-source-count").textContent = Number.isFinite(Number(knowledge.sources)) ? Number(knowledge.sources).toLocaleString("th-TH") : "—";
    $("#knowledge-chunk-count").textContent = Number.isFinite(Number(knowledge.chunks)) ? Number(knowledge.chunks).toLocaleString("th-TH") : "—";
    const preview = $("#memory-preview");
    preview.replaceChildren();
    $("#memory-empty").classList.toggle("hidden", memories.length > 0);
    for (const memory of memories.slice(0, 3)) {
      const row = element("div", "memory-item");
      const meta = element("div", "memory-meta");
      meta.append(element("span", "", categoryLabels[memory.category] || memory.category));
      meta.append(element("small", "", `${Math.round((Number(memory.confidence) || 0) * 100)}%`));
      row.append(meta, element("p", "", memory.content));
      preview.append(row);
    }
  }

  const permissionLabels = {
    granted: "อนุญาตแล้ว", denied: "ถูกปิด", prompt: "ยังไม่อนุญาต",
    unsupported: "ไม่รองรับ", insecure: "ต้องใช้ HTTPS"
  };

  function permissionSecureContext() {
    return window.isSecureContext || ["localhost", "127.0.0.1", "::1"].includes(window.location.hostname);
  }

  function showPermissionHelp(name, insecure = false) {
    const subject = { location: "ตำแหน่ง", microphone: "ไมโครโฟน", notifications: "การแจ้งเตือน" }[name] || "สิทธิ์นี้";
    const message = insecure
      ? `บน iPhone ต้องเปิด Minikun ผ่าน HTTPS ก่อนจึงจะใช้${subject}ได้ครับ`
      : `บน iPhone ให้แตะ aA → การตั้งค่าเว็บไซต์ → ${subject} → อนุญาต แล้วกลับมาลองอีกครั้งครับ`;
    $("#permission-note").textContent = message;
    toast(message, true);
  }

  function setPermissionUi(name, status, detail = "") {
    const statusNode = $(`#${name}-state`);
    const button = $(`#request-${name === "notifications" ? "notifications" : name}`);
    statusNode.textContent = permissionLabels[status] || status;
    statusNode.dataset.state = status;
    if (detail) $(`#${name}-detail`).textContent = detail;
    button.disabled = status === "unsupported";
    button.textContent = status === "granted"
      ? (name === "location" ? "อัปเดต" : "ทดสอบ")
      : status === "denied" ? "ลองอีกครั้ง"
        : status === "insecure" ? "ดูวิธีเปิด" : "เปิดใช้";
  }

  async function browserPermission(name) {
    if (!navigator.permissions?.query) return "prompt";
    try { return (await navigator.permissions.query({ name })).state; }
    catch (_) { return "prompt"; }
  }

  async function renderPermissions() {
    const secure = permissionSecureContext();
    const locationState = !secure ? "insecure"
      : navigator.geolocation ? await browserPermission("geolocation") : "unsupported";
    const microphoneState = !secure ? "insecure"
      : navigator.mediaDevices?.getUserMedia ? await browserPermission("microphone") : "unsupported";
    const notificationState = !secure ? "insecure" : "Notification" in window
      ? (Notification.permission === "default" ? "prompt" : Notification.permission) : "unsupported";
    setPermissionUi("location", locationState, state.currentLocation
      ? `ใช้กับแชตนี้ · แม่นยำประมาณ ${state.currentLocation.accuracy} ม.` : "ใช้กับคำถามในแชตขณะที่หน้านี้เปิดอยู่");
    setPermissionUi("microphone", microphoneState, "ใช้คุยและถอดเสียง");
    setPermissionUi("notifications", notificationState, "อนุญาตให้หน้าเว็บแสดงการแจ้งเตือน");
    $("#permission-note").textContent = secure
      ? "มินิคุงจะขอสิทธิ์เมื่อพี่สาวแตะเท่านั้น ถ้าเคยปิด ให้แตะ aA → การตั้งค่าเว็บไซต์ แล้วกลับมาลองอีกครั้งครับ"
      : "Safari บน iPhone อนุญาตตำแหน่ง ไมโครโฟน และการแจ้งเตือนเฉพาะหน้า HTTPS เท่านั้นครับ";
  }

  function requestCurrentLocation() {
    if (!permissionSecureContext()) {
      setPermissionUi("location", "insecure");
      showPermissionHelp("location", true);
      return;
    }
    if (!navigator.geolocation) {
      setPermissionUi("location", "unsupported");
      return;
    }
    const button = $("#request-location");
    button.disabled = true;
    button.textContent = "กำลังหา";
    navigator.geolocation.getCurrentPosition((position) => {
      state.currentLocation = {
        latitude: Number(position.coords.latitude.toFixed(6)),
        longitude: Number(position.coords.longitude.toFixed(6)),
        accuracy: Math.round(position.coords.accuracy)
      };
      setPermissionUi("location", "granted", `ใช้กับแชตนี้ · แม่นยำประมาณ ${state.currentLocation.accuracy} ม.`);
      toast("เปิดตำแหน่งให้แชตนี้แล้วครับ");
    }, (error) => {
      setPermissionUi("location", error.code === 1 ? "denied" : "prompt");
      if (error.code === 1) showPermissionHelp("location");
      else toast("อ่านตำแหน่งไม่สำเร็จ ลองอีกครั้งได้ครับ", true);
    }, { enableHighAccuracy: true, timeout: 12000, maximumAge: 60000 });
  }

  async function requestMicrophonePermission() {
    if (!permissionSecureContext()) {
      setPermissionUi("microphone", "insecure");
      showPermissionHelp("microphone", true);
      return;
    }
    if (!navigator.mediaDevices?.getUserMedia) {
      setPermissionUi("microphone", "unsupported");
      return;
    }
    const button = $("#request-microphone");
    button.disabled = true;
    button.textContent = "กำลังขอ";
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      stream.getTracks().forEach((track) => track.stop());
      setPermissionUi("microphone", "granted");
      toast("ไมโครโฟนพร้อมใช้แล้วครับ");
    } catch (_) {
      setPermissionUi("microphone", "denied");
      showPermissionHelp("microphone");
    }
  }

  async function requestNotificationPermission() {
    if (!permissionSecureContext()) {
      setPermissionUi("notifications", "insecure");
      showPermissionHelp("notifications", true);
      return;
    }
    if (!("Notification" in window)) {
      setPermissionUi("notifications", "unsupported");
      return;
    }
    const result = await Notification.requestPermission();
    setPermissionUi("notifications", result);
    if (result === "denied") showPermissionHelp("notifications");
    else toast(result === "granted" ? "อนุญาตการแจ้งเตือนแล้วครับ" : "ยังไม่ได้เปิดการแจ้งเตือนครับ", result !== "granted");
  }

  async function loadDashboard() {
    setSync(true, "กำลังทบทวน");
    renderPermissions();
    const calls = await Promise.allSettled([
      api("/v1/personal/status"),
      api("/v1/personal/next-actions?limit=5"),
      api("/v1/personal/experiments?limit=20"),
      api("/v1/personal/inbox?limit=20"),
      api(`/v1/personal/timeline?limit=${state.timelineLimit}`),
      api("/v1/personal/automations/runs?limit=20"),
      api("/v1/agent/runs?limit=12"),
      api("/v1/memory?limit=30"),
      api("/v1/knowledge/status"),
      api("/v1/system/health")
    ]);
    const [status, actions, experiments, inbox, timeline, decisions, agentRuns, memories, knowledge, systemHealth] = calls;
    const decisionCount = renderDecisions(decisions.status === "fulfilled" ? decisions.value : []);
    renderStatus(status.status === "fulfilled" ? status.value : {}, decisionCount);
    renderActions(actions.status === "fulfilled" ? actions.value : []);
    renderExperiment(experiments.status === "fulfilled" ? experiments.value : []);
    renderInbox(inbox.status === "fulfilled" ? inbox.value : []);
    renderTimeline(timeline.status === "fulfilled" ? timeline.value : []);
    const runItems = agentRuns.status === "fulfilled" ? agentRuns.value : [];
    renderAgentRuns(runItems);
    await loadToolTimeline(runItems);
    renderContextMemory(memories.status === "fulfilled" ? memories.value : [], knowledge.status === "fulfilled" ? knowledge.value : {});
    renderSystemHealth(systemHealth.status === "fulfilled" ? systemHealth.value : null);
    const failures = calls.filter((call) => call.status === "rejected");
    setSync(failures.length === 0, failures.length ? "มีบางส่วนยังไม่พร้อม" : "พร้อมดูแล");
    if (failures.length === calls.length) toast("เชื่อมกับมินิคุงไม่ได้ ลองตรวจ Owner ID หรือ token ครับ", true);
  }

  async function completeTask(id) {
    try {
      await api(`/v1/tasks/${id}/complete`, { method: "POST" });
      toast("ปิดงานให้แล้วครับ");
      await loadDashboard();
    } catch (error) { toast(error.message, true); }
  }

  async function resumeAgentRun(id, button) {
    button.disabled = true;
    button.textContent = "กำลังเริ่ม";
    try {
      await api(`/v1/agent/runs/${id}/resume`, { method: "POST" });
      toast("มินิคุงเริ่มทำงานนี้ต่อแล้วครับ");
      await loadDashboard();
    } catch (error) {
      toast(error.message, true);
      button.disabled = false;
      button.textContent = "ลองทำต่อ";
    }
  }

  async function transitionExperiment(id, action, note = "") {
    try {
      await api(`/v1/personal/experiments/${id}/transition`, {
        method: "POST", body: JSON.stringify({ action, note })
      });
      const labels = { START: "เริ่มการทดลองแล้วครับ", PAUSE: "พักไว้ให้แล้วครับ", RESUME: "กลับมาทดลองต่อแล้วครับ" };
      toast(labels[action] || "อัปเดตการทดลองแล้วครับ");
      await loadDashboard();
    } catch (error) { toast(error.message, true); }
  }

  function openExperimentAction(item, action) {
    state.experimentAction = { id: item.experiment.id, action };
    const copy = {
      CHECK_IN: ["บันทึกผลวันนี้", `ค่าล่าสุดของ ${item.experiment.metricName} เป็นเท่าไรครับ`, "บันทึกเช็กอิน"],
      COMPLETE: ["สรุปการทดลอง", "จบการทดลองและเก็บผลไว้เรียนรู้ โดยยังไม่ตีความเกินข้อมูลที่มีครับ", "สรุปผล"],
      ABANDON: ["หยุดการทดลอง", "บอกเหตุผลสั้น ๆ ได้ครับ เพื่อไม่ให้มินิคุงเสนอแบบเดิมผิดจังหวะ", "ยืนยันหยุด"],
      EVALUATE: ["ให้คะแนนผลลัพธ์", "คะแนนนี้จะเข้า Outcome Learning จากคำตอบที่พี่สาวให้โดยตรงครับ", "บันทึกคะแนน"]
    }[action];
    $("#experiment-action-title").textContent = copy[0];
    $("#experiment-action-copy").textContent = copy[1];
    $("#experiment-action-submit").textContent = copy[2];
    $("#experiment-value-field").classList.toggle("hidden", action !== "CHECK_IN");
    $("#experiment-score-field").classList.toggle("hidden", action !== "EVALUATE");
    $("#experiment-value").required = action === "CHECK_IN";
    $("#experiment-note").value = "";
    experimentActionDialog.showModal();
    if (action === "CHECK_IN") $("#experiment-value").focus();
  }

  async function submitExperimentAction(event) {
    if (event.submitter?.value === "cancel") return;
    event.preventDefault();
    const current = state.experimentAction;
    if (!current) return;
    const button = $("#experiment-action-submit");
    button.disabled = true;
    try {
      const note = $("#experiment-note").value;
      if (current.action === "CHECK_IN") {
        await api(`/v1/personal/experiments/${current.id}/check-ins`, {
          method: "POST", body: JSON.stringify({ value: Number($("#experiment-value").value), note })
        });
        toast("บันทึกผลวันนี้แล้วครับ");
      } else if (current.action === "EVALUATE") {
        await api(`/v1/personal/experiments/${current.id}/evaluate`, {
          method: "POST", body: JSON.stringify({ score: Number($("#experiment-score").value), note })
        });
        toast("มินิคุงรับผลไว้เรียนรู้แล้วครับ");
      } else {
        await api(`/v1/personal/experiments/${current.id}/transition`, {
          method: "POST", body: JSON.stringify({ action: current.action, note })
        });
        toast(current.action === "COMPLETE" ? "สรุปการทดลองแล้วครับ" : "หยุดการทดลองให้แล้วครับ");
      }
      experimentActionDialog.close();
      await loadDashboard();
    } catch (error) { toast(error.message, true); }
    finally { button.disabled = false; }
  }

  async function createExperiment(event) {
    if (event.submitter?.value === "cancel") return;
    event.preventDefault();
    const form = event.currentTarget;
    const values = Object.fromEntries(new FormData(form));
    const payload = {
      ...values, conversationId: "cockpit", baselineValue: Number(values.baselineValue),
      targetValue: Number(values.targetValue), durationDays: Number(values.durationDays)
    };
    if ((payload.direction === "INCREASE" && payload.targetValue <= payload.baselineValue)
        || (payload.direction === "DECREASE" && payload.targetValue >= payload.baselineValue)) {
      toast("เป้าหมายต้องไปในทิศทางเดียวกับที่เลือกครับ", true);
      return;
    }
    const submit = form.querySelector(".primary-button");
    submit.disabled = true;
    try {
      await api("/v1/personal/experiments", { method: "POST", body: JSON.stringify(payload) });
      form.reset();
      experimentDialog.close();
      toast("สร้างฉบับร่างแล้วครับ พร้อมเริ่มเมื่อพี่สาวต้องการ");
      await loadDashboard();
    } catch (error) { toast(error.message, true); }
    finally { submit.disabled = false; }
  }

  async function decideInbox(id, commit) {
    try {
      await api(`/v1/personal/inbox/${id}/${commit ? "commit" : "dismiss"}`, { method: "POST" });
      toast(commit ? "จัดเก็บให้แล้วครับ" : "นำออกจาก Inbox แล้วครับ");
      await loadDashboard();
    } catch (error) { toast(error.message, true); }
  }

  async function decideAutomation(id, approve) {
    try {
      await api(`/v1/personal/automations/runs/${id}/decision`, {
        method: "POST", body: JSON.stringify({ approve })
      });
      toast(approve ? "อนุมัติและดำเนินการแล้วครับ" : "ปฏิเสธรายการแล้วครับ");
      await loadDashboard();
    } catch (error) { toast(error.message, true); }
  }

  async function capture(event) {
    event.preventDefault();
    const input = $("#capture-input");
    try {
      await api("/v1/personal/inbox", {
        method: "POST",
        body: JSON.stringify({ conversationId: "cockpit", inputType: "TEXT", content: input.value })
      });
      input.value = "";
      toast("รับไว้แล้วครับ มินิคุงจะจัดประเภทให้ก่อนบันทึกจริง");
      await loadDashboard();
    } catch (error) { toast(error.message, true); }
  }

  $("#today-label").textContent = thaiDate();
  const hour = new Date().getHours();
  $("#greeting").textContent = hour < 12 ? "อรุณสวัสดิ์ครับ พี่สาว"
    : hour < 17 ? "สวัสดีตอนบ่ายครับ พี่สาว" : "สวัสดีตอนเย็นครับ พี่สาว";
  $("#owner-id").value = state.ownerId;
  $("#personal-token").value = state.token;
  $("#chat-model").value = state.model;
  $("#model-pill").textContent = state.model.toUpperCase();
  $("#voice-output").setAttribute("aria-pressed", String(state.voiceOutput));
  if (isAppleMobile()) {
    $("#composer-hint").textContent = "Enter ขึ้นบรรทัดใหม่ · แตะ ↑ เพื่อส่ง";
  }

  state.conversations = safeConversationList();
  if (state.conversations.length) {
    state.currentConversationId = state.conversations[0].id;
    state.chatMessages = Array.isArray(state.conversations[0].messages) ? state.conversations[0].messages : [];
  } else {
    state.currentConversationId = chatId();
  }
  renderConversationList();
  renderChat();
  restoreDraft();

  document.querySelectorAll("[data-close-dialog]").forEach((button) => {
    button.addEventListener("click", () => button.closest("dialog")?.close("cancel"));
  });
  document.querySelectorAll("dialog").forEach((dialog) => {
    dialog.addEventListener("click", (event) => {
      if (event.target === dialog) dialog.close("cancel");
    });
  });
  document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    const openDialogs = document.querySelectorAll("dialog[open]");
    const dialog = openDialogs[openDialogs.length - 1];
    if (!dialog) return;
    event.preventDefault();
    dialog.close("cancel");
  });

  document.querySelectorAll("[data-view-target]").forEach((button) => {
    button.addEventListener("click", () => {
      mobileMenuDialog?.close();
      showView(button.dataset.viewTarget, button.dataset.section || "");
    });
  });
  document.querySelectorAll("[data-prompt]").forEach((button) => {
    button.addEventListener("click", () => {
      const composer = $("#chat-composer");
      composer.value = button.dataset.prompt;
      autoGrowComposer();
      composer.focus();
    });
  });
  $("#new-chat").addEventListener("click", startNewChat);
  $("#mobile-new-chat").addEventListener("click", startNewChat);
  document.querySelectorAll("[data-open-mobile-menu]").forEach((button) => {
    button.addEventListener("click", () => mobileMenuDialog.showModal());
  });
  $("#mobile-menu-new-chat").addEventListener("click", () => {
    mobileMenuDialog.close();
    startNewChat();
  });
  $("#chat-form").addEventListener("submit", sendChat);
  $("#chat-composer").addEventListener("input", () => { autoGrowComposer(); saveDraft(); });
  $("#chat-composer").addEventListener("keydown", (event) => {
    if (!isAppleMobile() && event.key === "Enter" && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      sendChat();
    }
  });
  $("#attach-file").addEventListener("click", () => $("#file-input").click());
  $("#file-input").addEventListener("change", async (event) => {
    await addFiles(event.target.files);
    event.target.value = "";
  });
  $("#record-voice").addEventListener("click", toggleRecording);
  $("#stop-message").addEventListener("click", () => state.pendingChats.get(state.currentConversationId)?.controller.abort());
  $("#voice-output").addEventListener("click", () => {
    state.voiceOutput = !state.voiceOutput;
    localStorage.setItem("minikun.voice-output", String(state.voiceOutput));
    $("#voice-output").setAttribute("aria-pressed", String(state.voiceOutput));
    if (!state.voiceOutput) {
      stopSpeech();
      toast("ปิดการอ่านคำตอบแล้วครับ");
      return;
    }
    const latest = [...state.chatMessages].reverse().find((message) => message.role === "assistant" && message.content);
    if (latest) speak(latest.content);
    toast(latest ? "กำลังอ่านคำตอบล่าสุดครับ" : "จะอ่านคำตอบใหม่ออกเสียงครับ");
  });
  $("#messages").addEventListener("click", async (event) => {
    const button = event.target.closest("[data-copy-code]");
    if (!button) return;
    try {
      await copyText(button.closest(".code-block")?.querySelector("code")?.textContent || "");
      button.textContent = "คัดลอกแล้ว";
      setTimeout(() => { button.textContent = "คัดลอก"; }, 1400);
    } catch (error) { toast(error.message, true); }
  });
  $("#clear-chat-list").addEventListener("click", () => clearHistoryDialog.showModal());
  $("#mobile-clear-chat-list").addEventListener("click", () => {
    mobileMenuDialog.close();
    clearHistoryDialog.showModal();
  });
  $("#confirm-clear-history").addEventListener("click", async (event) => {
    event.preventDefault();
    try {
      await Promise.all(state.conversations.map((conversation) =>
        api(`/v1/conversations/${encodeURIComponent(conversation.id)}`, { method: "DELETE" })));
      if (state.sync.paired) await syncFetch("/v1/sync/conversations", { method: "DELETE" });
      localStorage.removeItem("minikun.conversations");
      state.conversations = [];
      startNewChat();
      clearHistoryDialog.close("confirm");
      toast(state.sync.paired ? "ล้างรายการบทสนทนาจากทุกอุปกรณ์แล้วครับ" : "ล้างรายการบนอุปกรณ์นี้แล้วครับ");
    } catch (error) { toast(error.message, true); }
  });
  document.querySelectorAll("[data-conversation-search]").forEach((input) => {
    input.addEventListener("input", () => {
      state.conversationQuery = input.value;
      document.querySelectorAll("[data-conversation-search]").forEach((peer) => {
        if (peer !== input) peer.value = input.value;
      });
      renderConversationList();
    });
  });
  document.querySelectorAll("[data-archive-toggle]").forEach((button) => {
    button.addEventListener("click", () => {
      state.showArchived = !state.showArchived;
      document.querySelectorAll("[data-archive-toggle]").forEach((peer) => {
        peer.textContent = state.showArchived ? "กลับไปแชตปัจจุบัน" : "ดูคลังแชต";
      });
      renderConversationList();
    });
  });
  $("#rename-conversation").addEventListener("click", () => {
    const conversation = actionConversation();
    const title = $("#conversation-title-input").value.trim();
    if (!conversation || !title) return;
    conversation.title = title;
    touchConversation(conversation);
    $("#conversation-actions-dialog").close();
    toast("เปลี่ยนชื่อบทสนทนาแล้วครับ");
  });
  $("#pin-conversation").addEventListener("click", () => {
    const conversation = actionConversation();
    if (!conversation) return;
    conversation.pinned = !conversation.pinned;
    touchConversation(conversation);
    openConversationActions(conversation.id);
  });
  $("#archive-conversation").addEventListener("click", () => {
    const conversation = actionConversation();
    if (!conversation) return;
    conversation.archived = !conversation.archived;
    touchConversation(conversation);
    $("#conversation-actions-dialog").close();
    if (conversation.archived && state.currentConversationId === conversation.id) {
      const next = state.conversations.find((item) => !item.archived);
      if (next) switchConversation(next.id); else startNewChat();
    }
    toast(conversation.archived ? "เก็บบทสนทนาเข้าคลังแล้วครับ" : "นำบทสนทนากลับแล้วครับ");
  });
  $("#duplicate-conversation").addEventListener("click", () => {
    const conversation = actionConversation();
    if (!conversation) return;
    if (state.currentConversationId !== conversation.id) switchConversation(conversation.id);
    branchFromMessage(state.chatMessages.length - 1);
    $("#conversation-actions-dialog").close();
  });
  $("#export-conversation").addEventListener("click", () => {
    const conversation = actionConversation();
    if (conversation) exportConversation(conversation);
  });
  $("#export-conversation-json").addEventListener("click", () => {
    const conversation = actionConversation();
    if (conversation) exportConversationJson(conversation);
  });
  $("#delete-conversation").addEventListener("click", () => deleteConversation(actionConversation()));
  window.addEventListener("beforeunload", () => {
    state.mediaStream?.getTracks().forEach((track) => track.stop());
    state.audioContext?.close();
    state.sync.eventSource?.close();
    stopSpeech();
  });

  $("#open-settings").addEventListener("click", () => {
    settingsDialog.showModal();
    loadPairedDevices();
  });
  $("#mobile-menu-settings").addEventListener("click", () => {
    mobileMenuDialog.close();
    settingsDialog.showModal();
    loadPairedDevices();
  });
  $("#create-pairing").addEventListener("click", createDevicePairing);
  $("#copy-pair-link").addEventListener("click", async () => {
    try {
      await copyText(state.sync.pairingUrl);
      toast("คัดลอกลิงก์จับคู่แล้วครับ");
    } catch (error) { toast(error.message, true); }
  });
  $("#refresh").addEventListener("click", loadDashboard);
  document.querySelectorAll("[data-quick-action]").forEach((button) => {
    button.addEventListener("click", () => activateQuickAction(button.dataset.quickAction));
  });
  document.querySelectorAll("[data-cockpit-target]").forEach((button) => {
    button.addEventListener("click", () => {
      switchCockpitPage(button.dataset.cockpitTarget);
    });
  });
  $("#request-location").addEventListener("click", requestCurrentLocation);
  $("#request-microphone").addEventListener("click", requestMicrophonePermission);
  $("#request-notifications").addEventListener("click", requestNotificationPermission);
  $("#new-experiment").addEventListener("click", () => experimentDialog.showModal());
  document.querySelectorAll("[data-open-experiment]").forEach((button) => {
    button.addEventListener("click", () => experimentDialog.showModal());
  });
  $("#capture-form").addEventListener("submit", capture);
  $("#experiment-form").addEventListener("submit", createExperiment);
  $("#experiment-action-form").addEventListener("submit", submitExperimentAction);
  $("#settings-form").addEventListener("submit", (event) => {
    if (event.submitter?.value === "cancel") return;
    event.preventDefault();
    state.ownerId = $("#owner-id").value.trim() || "default";
    state.token = $("#personal-token").value;
    state.model = $("#chat-model").value.trim() || "mini-kun";
    sessionStorage.setItem("minikun.owner", state.ownerId);
    sessionStorage.setItem("minikun.token", state.token);
    localStorage.setItem("minikun.model", state.model);
    $("#model-pill").textContent = state.model.toUpperCase();
    settingsDialog.close();
    toast("บันทึกการตั้งค่าแล้วครับ");
  });
  $("#show-all-timeline").addEventListener("click", () => {
    state.timelineLimit = Math.min(50, state.timelineLimit + 10);
    loadDashboard();
  });

  showView("chat");
  initializeSync();
})();
