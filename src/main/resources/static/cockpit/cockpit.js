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
    cockpitPage: "today",
    timelineLimit: 6,
    learningBusy: new Set(),
    experimentAction: null,
    currentLocation: null,
    activeVisual: null,
    studio: {
      style: "",
      light: "",
      frame: "",
      width: 768,
      height: 1280,
      result: null,
      busy: false,
      transforming: false,
      runtimeTimer: null,
      runtimeBusy: false,
      runtimeOnline: false,
      runtimeHistory: []
    },
    sync: {
      paired: false,
      deviceId: "",
      canonicalOrigin: "https://mini-kun:8443",
      timers: new Map(),
      inFlight: new Map(),
      eventSource: null,
      pairingUrl: "",
      refreshing: false,
      remoteDirty: false,
      pending: new Set(),
      revision: 0
    },
    dashboard: {
      timer: null,
      loading: new Map(),
      loadedAt: new Map(),
      scroll: new Map()
    }
  };

  const $ = (selector) => document.querySelector(selector);
  const Core = globalThis.MinikunCore;
  const settingsDialog = $("#settings-dialog");
  const mobileMenuDialog = $("#mobile-menu-dialog");
  const experimentDialog = $("#experiment-dialog");
  const learningTopicDialog = $("#learning-topic-dialog");
  const experimentActionDialog = $("#experiment-action-dialog");
  const clearHistoryDialog = $("#clear-history-dialog");
  const visualLightboxDialog = $("#visual-lightbox-dialog");
  const inspirationBoardsDialog = $("#inspiration-boards-dialog");
  const confirmDialog = $("#confirm-dialog");
  const feedbackDialog = $("#feedback-dialog");
  const editMessageDialog = $("#edit-message-dialog");
  let confirmationResolve = null;
  let feedbackResolve = null;
  let editMessageResolve = null;

  function headers(json = false) {
    return Core.authHeaders(state.token, json);
  }

  async function api(path, options = {}) {
    return Core.request(path, options, { ownerId: state.ownerId, token: state.token });
  }

  function showActiveModel(model) {
    state.model = model;
    localStorage.setItem("minikun.model", model);
    const pill = $("#model-pill");
    pill.textContent = model.length > 28 ? `${model.slice(0, 25)}…` : model;
    pill.title = model;
  }

  async function loadRuntimeModels() {
    const catalog = await api("/v1/models/runtime");
    const select = $("#chat-model");
    const available = catalog.models.includes(catalog.active)
      ? catalog.models : [catalog.active, ...catalog.models].filter(Boolean);
    select.replaceChildren(...available.map((model) => {
      const option = document.createElement("option");
      option.value = model;
      option.textContent = model;
      return option;
    }));
    select.value = catalog.active;
    showActiveModel(catalog.active);
  }

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function normalizeVisual(attachment = {}) {
    const originalUrl = attachment.original_url || attachment.originalUrl || attachment.image_url?.url
      || attachment.remoteUrl || attachment.url || "";
    return {
      title: attachment.title || "Visual reference",
      url: attachment.url || originalUrl,
      originalUrl,
      sourceUrl: attachment.source_url || attachment.sourceUrl || "",
      description: attachment.description || "",
      origin: attachment.origin || "web",
      provider: attachment.provider || "",
      license: attachment.license || "",
      thumbnailUrl: attachment.thumbnail_url || attachment.thumbnailUrl || "",
      width: attachment.width || null,
      height: attachment.height || null,
      prompt: attachment.prompt || "",
      negativePrompt: attachment.negative_prompt || attachment.negativePrompt || "",
      seed: attachment.seed ?? null,
      generationId: attachment.generation_id || attachment.generationId || ""
    };
  }

  function visualOriginLabel(origin) {
    return origin === "generated" ? "มินิคุงสร้างให้" : origin === "user" ? "ภาพของเรา" : "ภาพจากเว็บ";
  }

  function visualHost(visual) {
    try { return new URL(visual.sourceUrl || visual.originalUrl).hostname.replace(/^www\./, "")
      || visual.provider || "แหล่งภาพ"; }
    catch (_) { return visual.provider || "แหล่งภาพ"; }
  }

  function safeExternalUrl(url) {
    try {
      const parsed = new URL(url);
      return parsed.protocol === "https:" || parsed.protocol === "http:" ? parsed.href : "";
    } catch (_) { return ""; }
  }

  function visualMetadata(visual) {
    const dimensions = visual.width && visual.height ? `${visual.width}×${visual.height}` : "";
    return [visual.provider || visualHost(visual), dimensions, visual.license]
      .filter(Boolean).filter((value, index, values) => values.indexOf(value) === index).join(" · ");
  }

  function proxyImageUrl(url) {
    if (!url || url.startsWith("/") || url.startsWith("data:")) return url;
    return `/v1/images/proxy?url=${encodeURIComponent(url)}`;
  }

  function openVisualLightbox(attachment) {
    const visual = normalizeVisual(attachment);
    state.activeVisual = visual;
    $("#visual-origin-label").textContent = visualOriginLabel(visual.origin);
    $("#visual-lightbox-title").textContent = visual.title;
    const lightboxImage = $("#visual-lightbox-image");
    lightboxImage.dataset.thumbnailFallback = "false";
    lightboxImage.classList.remove("visual-broken");
    lightboxImage.src = visual.url || proxyImageUrl(visual.originalUrl);
    lightboxImage.alt = visual.title;
    lightboxImage.onerror = () => {
      if (visual.thumbnailUrl && lightboxImage.dataset.thumbnailFallback !== "true") {
        lightboxImage.dataset.thumbnailFallback = "true";
        lightboxImage.src = proxyImageUrl(visual.thumbnailUrl);
        return;
      }
      lightboxImage.classList.add("visual-broken");
      lightboxImage.alt = "โหลดภาพนี้ไม่สำเร็จ";
    };
    const metadata = visualMetadata(visual);
    $("#visual-lightbox-description").textContent = [visual.description, metadata]
      .filter(Boolean).join("\n");
    renderLightboxGenerationMetadata(visual);
    const source = $("#visual-lightbox-source");
    const sourceUrl = safeExternalUrl(visual.sourceUrl || visual.originalUrl);
    source.href = sourceUrl || "#";
    source.classList.toggle("hidden", !sourceUrl);
    if (!visualLightboxDialog.open) visualLightboxDialog.showModal();
  }

  function copyGenerationButton(label, value) {
    const button = element("button", "generation-copy", label);
    button.type = "button";
    button.addEventListener("click", async () => {
      try {
        await copyText(value);
        const previous = button.textContent;
        button.textContent = "คัดลอกแล้ว";
        setTimeout(() => { button.textContent = previous; }, 1400);
      } catch (error) { toast(error.message, true); }
    });
    return button;
  }

  function generationMetadataPanel(visual, compact = false) {
    if (visual.origin !== "generated" || (!visual.prompt && visual.seed === null)) return null;
    const panel = element("section", compact ? "generation-meta compact" : "generation-meta");
    const heading = element("div", "generation-meta-heading");
    heading.append(element("strong", "", "ข้อมูลการสร้างภาพ"));
    if (visual.seed !== null) {
      const seed = element("span", "generation-seed", `Seed ${visual.seed}`);
      heading.append(seed, copyGenerationButton("คัดลอก seed", String(visual.seed)));
    }
    panel.append(heading);
    if (visual.prompt) {
      const details = element("details", "generation-prompt");
      if (!compact) details.open = true;
      details.append(element("summary", "", "ดู Pony prompt"));
      const prompt = element("code", "", visual.prompt);
      details.append(prompt, copyGenerationButton("คัดลอก prompt", visual.prompt));
      panel.append(details);
    }
    return panel;
  }

  function renderLightboxGenerationMetadata(visual) {
    let panel = $("#visual-generation-meta");
    if (!panel) {
      panel = element("div");
      panel.id = "visual-generation-meta";
      $("#visual-lightbox-description").after(panel);
    }
    panel.replaceChildren();
    const metadata = generationMetadataPanel(visual);
    panel.classList.toggle("hidden", !metadata);
    if (metadata) panel.append(metadata);
  }

  function attachVisualReference(visual, create = false) {
    const reference = normalizeVisual(visual);
    state.attachments.push({
      kind: "image", name: reference.title, type: "image/remote",
      remoteUrl: reference.originalUrl, previewUrl: reference.url,
      sourceUrl: reference.sourceUrl, origin: reference.origin
    });
    renderAttachmentTray();
    const composer = $("#chat-composer");
    composer.value = create
      ? `ใช้ภาพ reference ที่แนบมานี้เป็นทิศทาง แล้วช่วยออกแบบภาพใหม่ให้ต่างจากต้นฉบับอย่างชัดเจน: `
      : `ช่วยดูและคุยต่อจากภาพ reference นี้หน่อยครับ: `;
    autoGrowComposer();
    visualLightboxDialog.close();
    composer.focus();
    toast(create ? "แนบ reference สำหรับสร้างงานต่อแล้วครับ" : "แนบรูปไว้ถามต่อแล้วครับ");
  }

  function findSimilarVisual(visual) {
    attachVisualReference(visual);
    const composer = $("#chat-composer");
    composer.value = `ช่วยสรุปรายละเอียดจากภาพ reference นี้เป็นคำค้น แล้วค้นหารูปที่มีสไตล์หรือบรรยากาศใกล้เคียง พร้อมบอกแหล่งที่มาของแต่ละรูปครับ: `;
    autoGrowComposer();
    composer.focus();
    toast("แนบภาพให้ AI สรุปคำค้นเพื่อค้นหารูปคล้ายกันแล้วครับ");
  }

  async function defaultInspirationBoard() {
    let boards = await api("/v1/personal/inspiration-boards");
    if (!boards.length) {
      const created = await api("/v1/personal/inspiration-boards", {
        method: "POST", body: JSON.stringify({ owner_id: state.ownerId, title: "แรงบันดาลใจ" })
      });
      boards = [created];
    }
    return boards[0];
  }

  async function saveVisualReference(attachment) {
    const visual = normalizeVisual(attachment);
    const board = await defaultInspirationBoard();
    await api(`/v1/personal/inspiration-boards/${board.id}/items`, {
      method: "POST", body: JSON.stringify({
        owner_id: state.ownerId, image_url: visual.originalUrl, source_url: visual.sourceUrl,
        title: visual.title, description: visual.description, origin: visual.origin
      })
    });
    toast(`เก็บไว้ในบอร์ด “${board.title}” แล้วครับ`);
  }

  async function loadInspirationBoards() {
    const list = $("#inspiration-board-list");
    list.replaceChildren(element("p", "dialog-copy", "กำลังเปิดบอร์ด…"));
    try {
      const boards = await api("/v1/personal/inspiration-boards");
      list.replaceChildren();
      if (!boards.length) list.append(element("p", "dialog-copy", "ยังไม่มีบอร์ดครับ เก็บรูปแรกแล้วมินิคุงจะสร้างให้เอง"));
      for (const board of boards) {
        const section = element("section", "inspiration-board");
        const heading = element("div", "inspiration-board-heading");
        heading.append(element("h3", "", board.title));
        const removeBoard = element("button", "", "ลบบอร์ด");
        removeBoard.type = "button";
        removeBoard.addEventListener("click", async () => {
          inspirationBoardsDialog.close();
          if (!await requestConfirmation("ลบบอร์ดนี้?", `ลบบอร์ด “${board.title}” และ reference ทั้งหมดใช่ไหมครับ?`, "ลบบอร์ด")) {
            inspirationBoardsDialog.showModal();
            return;
          }
          try {
            await api(`/v1/personal/inspiration-boards/${board.id}`, { method: "DELETE" });
            await loadInspirationBoards();
          } catch (error) { toast(error.message, true); }
          finally { if (!inspirationBoardsDialog.open) inspirationBoardsDialog.showModal(); }
        });
        heading.append(removeBoard); section.append(heading);
        const grid = element("div", "inspiration-items");
        const items = await api(`/v1/personal/inspiration-boards/${board.id}/items`);
        for (const item of items) {
          const card = element("div", "inspiration-item");
          const image = element("img");
          image.src = proxyImageUrl(item.imageUrl);
          image.alt = item.title || "reference";
          image.addEventListener("click", () => openVisualLightbox({
            ...item, url: proxyImageUrl(item.imageUrl), originalUrl: item.imageUrl
          }));
          const remove = element("button", "", "×");
          remove.type = "button";
          remove.addEventListener("click", async () => {
            await api(`/v1/personal/inspiration-boards/${board.id}/items/${item.id}`, { method: "DELETE" });
            await loadInspirationBoards();
          });
          card.append(image, remove); grid.append(card);
        }
        if (!items.length) grid.append(element("p", "dialog-copy", "บอร์ดนี้ยังว่างครับ"));
        section.append(grid); list.append(section);
      }
    } catch (error) { list.replaceChildren(element("p", "dialog-copy", error.message)); }
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

  function toast(message, error = false, action = null) {
    const node = element("div", `toast${error ? " error" : ""}`);
    node.append(element("span", "", message));
    if (action?.label && typeof action.onClick === "function") {
      const button = element("button", "toast-action", action.label);
      button.type = "button";
      button.addEventListener("click", () => {
        action.onClick();
        node.remove();
      });
      node.append(button);
    }
    $("#toast-region").append(node);
    setTimeout(() => node.remove(), action ? 7200 : 3800);
  }

  function requestConfirmation(title, copy, confirmLabel = "ยืนยัน") {
    return new Promise((resolve) => {
      confirmationResolve = resolve;
      $("#confirm-title").textContent = title;
      $("#confirm-copy").textContent = copy;
      $("#confirm-action").textContent = confirmLabel;
      confirmDialog.showModal();
    });
  }

  function finishConfirmation(value) {
    const resolve = confirmationResolve;
    confirmationResolve = null;
    if (confirmDialog.open) confirmDialog.close(value ? "confirm" : "cancel");
    resolve?.(value);
  }

  function requestFeedback() {
    return new Promise((resolve) => {
      feedbackResolve = resolve;
      $("#feedback-reason").value = "";
      feedbackDialog.showModal();
      requestAnimationFrame(() => $("#feedback-reason").focus());
    });
  }

  function finishFeedback(value) {
    const resolve = feedbackResolve;
    feedbackResolve = null;
    if (feedbackDialog.open) feedbackDialog.close(value ? "submit" : "cancel");
    resolve?.(value);
  }

  function requestEditedMessage(value) {
    return new Promise((resolve) => {
      editMessageResolve = resolve;
      $("#edit-message-input").value = value;
      editMessageDialog.showModal();
      requestAnimationFrame(() => {
        const input = $("#edit-message-input");
        input.focus();
        input.setSelectionRange(input.value.length, input.value.length);
      });
    });
  }

  function finishEditedMessage(value) {
    const resolve = editMessageResolve;
    editMessageResolve = null;
    if (editMessageDialog.open) editMessageDialog.close(value ? "submit" : "cancel");
    resolve?.(value);
  }

  /* Chat --------------------------------------------------------------- */
  function uniqueId(prefix = "") {
    return Core.uniqueId(prefix);
  }

  function chatId() {
    return uniqueId("web-");
  }

  function safeConversationList() {
    return Core.loadConversations();
  }

  function persistConversations() {
    try {
      localStorage.setItem("minikun.conversations", JSON.stringify(state.conversations.slice(0, 40)));
    } catch (_) {
      toast("พื้นที่เก็บประวัติในเบราว์เซอร์เต็มครับ แชตยังทำงานต่อได้", true);
    }
  }

  function markConversationDirty(conversationId) {
    state.sync.revision += 1;
    state.sync.pending.add(conversationId);
    persistPendingSync();
  }

  function persistPendingSync() {
    try { localStorage.setItem("minikun.sync-pending", JSON.stringify([...state.sync.pending])); }
    catch (_) { toast("บันทึกคิวซิงก์ในเบราว์เซอร์ไม่สำเร็จครับ", true); }
  }

  function forgetConversationSync(conversationId) {
    window.clearTimeout(state.sync.timers.get(conversationId));
    state.sync.timers.delete(conversationId);
    state.sync.pending.delete(conversationId);
    persistPendingSync();
  }

  function retryPendingSync() {
    if (!state.sync.paired) return Promise.resolve([]);
    return Promise.all([...state.sync.pending].map(pushConversationSync));
  }

  function compactMessages(messages) {
    return Core.compactMessages(messages);
  }

  function normalizeConversations(values) {
    return Core.normalizeConversations(values);
  }

  function syncPayload(conversation) {
    return Core.syncPayload(conversation);
  }

  function defaultDeviceName() {
    return Core.defaultDeviceName();
  }

  async function syncFetch(path, options = {}) {
    return Core.syncRequest(path, options, { token: state.token });
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
      if (local.length && (session.bootstrapped || localStorage.getItem("minikun.sync-migrated") !== "true")) {
        await syncFetch("/v1/sync/conversations/import", { method: "POST", body: JSON.stringify(local) });
        localStorage.setItem("minikun.sync-migrated", "true");
      }
      await retryPendingSync();
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
    if (state.pendingChats.size || state.sync.pending.size || state.sync.timers.size || state.sync.inFlight.size) {
      state.sync.remoteDirty = true;
      return;
    }
    state.sync.refreshing = true;
    state.sync.remoteDirty = false;
    const revision = state.sync.revision;
    try {
      const remote = normalizeConversations(await syncFetch("/v1/sync/conversations"));
      if (revision !== state.sync.revision || state.pendingChats.size || state.sync.pending.size
          || state.sync.timers.size || state.sync.inFlight.size) {
        state.sync.remoteDirty = true;
        return;
      }
      const currentId = state.currentConversationId;
      state.conversations = remote;
      const current = remote.find((conversation) => conversation.id === currentId) || remote[0];
      if (current) {
        state.currentConversationId = current.id;
        state.chatMessages = current.messages;
      } else {
        state.currentConversationId = chatId();
        state.chatMessages = [];
      }
      persistConversations();
      renderConversationList();
      renderChat();
      syncChatState();
      resumeBackgroundChats();
    } finally {
      state.sync.refreshing = false;
      if (state.sync.remoteDirty && !state.pendingChats.size && !state.sync.pending.size
          && !state.sync.timers.size && !state.sync.inFlight.size) {
        queueMicrotask(refreshSyncedConversations);
      }
    }
  }

  function scheduleConversationSync(conversationId) {
    if (!state.sync.paired) return;
    window.clearTimeout(state.sync.timers.get(conversationId));
    state.sync.timers.set(conversationId, window.setTimeout(() => {
      state.sync.timers.delete(conversationId);
      pushConversationSync(conversationId);
    }, 220));
  }

  function pushConversationSync(conversationId) {
    if (!state.sync.paired) return Promise.resolve(false);
    window.clearTimeout(state.sync.timers.get(conversationId));
    state.sync.timers.delete(conversationId);
    const previous = state.sync.inFlight.get(conversationId) || Promise.resolve();
    const operation = previous.catch(() => false).then(async () => {
      const conversation = state.conversations.find((value) => value.id === conversationId);
      if (!conversation) return false;
      try {
        const payload = JSON.stringify(syncPayload(conversation));
        await syncFetch(`/v1/sync/conversations/${encodeURIComponent(conversationId)}`, {
          method: "PUT", body: payload
        });
        if (JSON.stringify(syncPayload(conversation)) === payload) {
          state.sync.pending.delete(conversationId);
          persistPendingSync();
        } else {
          scheduleConversationSync(conversationId);
        }
        renderSyncState(state.sync.pending.size ? "รอซิงก์" : "ซิงก์แล้ว",
          state.sync.pending.size ? "unpaired" : "paired");
        return true;
      } catch (error) {
        renderSyncState("รอซิงก์", "unpaired");
        return false;
      }
    });
    state.sync.inFlight.set(conversationId, operation);
    operation.then((synced) => {
      if (state.sync.inFlight.get(conversationId) === operation) {
        state.sync.inFlight.delete(conversationId);
      }
      if (synced && state.sync.remoteDirty && !state.pendingChats.size && !state.sync.pending.size
          && !state.sync.timers.size && !state.sync.inFlight.size) {
        refreshSyncedConversations();
      }
    });
    return operation;
  }

  function showBrowserNotification(payload, eventId = "", force = false) {
    const title = String(payload?.title || "Mini-kun").trim();
    const message = String(payload?.message || "").trim();
    if (!message) return;
    if (!force && document.visibilityState === "visible") {
      toast(message);
      return;
    }
    if (!("Notification" in window) || Notification.permission !== "granted") return;
    const tag = `minikun:${payload?.sourceType || "notification"}:${payload?.sourceId || eventId || Date.now()}`;
    const notification = new Notification(title, { body: message, tag, renotify: false });
    notification.onclick = () => {
      window.focus();
      notification.close();
    };
  }

  function connectSyncEvents() {
    state.sync.eventSource?.close();
    const source = new EventSource("/v1/sync/events");
    state.sync.eventSource = source;
    source.addEventListener("notification", (event) => {
      try {
        JSON.parse(event.data);
        showBrowserNotification(update.payload, event.lastEventId);
      } catch (_) { /* ignore malformed notification events */ }
    });
    source.addEventListener("sync", async (event) => {
      try {
        const update = JSON.parse(event.data);
        await refreshSyncedConversations();
        await loadPairedDevices();
        if (!state.sync.pending.size) renderSyncState("อัปเดตแล้ว", "paired");
      } catch (_) { /* EventSource reconnects and the next refresh repairs state */ }
    });
    source.onerror = () => renderSyncState("กำลังเชื่อมใหม่", "unpaired");
    source.onopen = async () => {
      await retryPendingSync();
      renderSyncState(state.sync.pending.size ? "รอซิงก์" : "ซิงก์แล้ว",
        state.sync.pending.size ? "unpaired" : "paired");
    };
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
            settingsDialog.close();
            if (!await requestConfirmation("ถอนสิทธิ์อุปกรณ์?", `ถอนสิทธิ์ ${device.name}?`, "ถอนสิทธิ์")) {
              settingsDialog.showModal();
              return;
            }
            try {
              await syncFetch(`/v1/sync/devices/${encodeURIComponent(device.id)}`, { method: "DELETE" });
              await loadPairedDevices();
              toast("ถอนสิทธิ์อุปกรณ์แล้วครับ");
            } catch (error) { toast(error.message, true); }
            finally { if (!settingsDialog.open) settingsDialog.showModal(); }
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
    return Core.conversationTitle(text);
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
    markConversationDirty(conversationId);
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
    loadCompanionMode();
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
    loadCompanionMode();
  }

  async function loadCompanionMode() {
    const select = $("#companion-mode");
    if (!select || !state.currentConversationId) return;
    try {
      const result = await api(`/v1/chat/companion-mode?conversation_id=${encodeURIComponent(state.currentConversationId)}`);
      select.value = result?.mode || "BALANCED";
    } catch (_) { select.value = "BALANCED"; }
  }

  async function saveCompanionMode() {
    const select = $("#companion-mode");
    if (!select || !state.currentConversationId) return;
    await api("/v1/chat/companion-mode", {
      method: "PUT",
      body: JSON.stringify({
        owner_id: state.ownerId,
        conversation_id: state.currentConversationId,
        mode: select.value
      })
    });
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
    markConversationDirty(conversation.id);
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
    if (!conversation) return;
    $("#conversation-actions-dialog").close();
    if (!await requestConfirmation("ลบบทสนทนา?", `ลบ “${conversation.title}” และบริบทระยะสั้นของแชตนี้?`, "ลบบทสนทนา")) {
      openConversationActions(conversation.id);
      return;
    }
    try {
      window.clearTimeout(state.sync.timers.get(conversation.id));
      state.sync.timers.delete(conversation.id);
      await state.sync.inFlight.get(conversation.id);
      await api(`/v1/conversations/${encodeURIComponent(conversation.id)}`, { method: "DELETE" });
      if (state.sync.paired) {
        await syncFetch(`/v1/sync/conversations/${encodeURIComponent(conversation.id)}`, { method: "DELETE" });
      }
      state.conversations = state.conversations.filter((item) => item.id !== conversation.id);
      forgetConversationSync(conversation.id);
      persistConversations();
      if (state.currentConversationId === conversation.id) {
        const next = state.conversations.find((item) => !item.archived);
        if (next) switchConversation(next.id); else startNewChat();
      } else {
        renderConversationList();
      }
      toast("ลบบทสนทนาและ short-term memory แล้วครับ");
    } catch (error) { toast(error.message, true); }
  }

  function updateViewUrl(view, section = "", replace = false) {
    const query = new URLSearchParams();
    if (view !== "chat") query.set("view", view);
    if (view === "cockpit" && section) query.set("section", section);
    const conversationId = new URLSearchParams(window.location.search).get("conversation_id");
    if (conversationId) query.set("conversation_id", conversationId);
    const next = `${window.location.pathname}${query.toString() ? `?${query}` : ""}${window.location.hash}`;
    const method = replace ? "replaceState" : "pushState";
    if (`${window.location.pathname}${window.location.search}${window.location.hash}` !== next) {
      window.history[method]({}, "", next);
    }
  }

  function showView(view, section = "", options = {}) {
    document.querySelectorAll("[data-view]").forEach((node) => node.classList.toggle("hidden", node.dataset.view !== view));
    document.querySelectorAll("[data-view-target]").forEach((node) => {
      const matchesView = node.dataset.viewTarget === view;
      const matchesSection = !node.dataset.section || !section || node.dataset.section === section;
      const active = matchesView && matchesSection;
      node.classList.toggle("active", active);
      if (active) node.setAttribute("aria-current", "page");
      else node.removeAttribute("aria-current");
    });
    if (view === "cockpit") {
      const cockpitPages = new Set(["today", "agent", "memory", "system"]);
      const permissionSection = section === "permission-center" || section === "permissions";
      const page = { experiments: "agent", inbox: "today", "conversation-threads": "agent" }[section]
        || (cockpitPages.has(section) ? section : state.cockpitPage || "today");
      switchCockpitPage(page, permissionSection ? "" : section);
      if (permissionSection) openSettings();
    }
    if (view === "studio") updateStudioPrompt();
    updateStudioRuntimePolling(view);
    updateSystemPolling();
    if (options.updateHistory) updateViewUrl(view, section);
    if (options.focus) requestAnimationFrame(() => $("#main-content")?.focus({ preventScroll: true }));
  }

  function switchCockpitPage(page = "today", focusId = "") {
    const legacyPages = {
      overview: "today", work: "today", experiments: "agent",
      tools: "agent", learning: "memory", health: "system", permissions: "system"
    };
    const requested = legacyPages[page] || page;
    const previousPage = state.cockpitPage;
    const allowed = new Set(["today", "agent", "memory", "system"]);
    state.cockpitPage = allowed.has(requested) ? requested : "today";
    loadDashboard(state.cockpitPage, { force: state.cockpitPage === "system" }).catch((error) => toast(error.message, true));
    updateSystemPolling();
    if (previousPage !== state.cockpitPage) state.dashboard.scroll.set(previousPage, window.scrollY);
    document.querySelectorAll("[data-cockpit-page]").forEach((node) => {
      const pages = String(node.dataset.cockpitPage || "").split(/\s+/);
      node.classList.toggle("cockpit-page-hidden", !pages.includes(state.cockpitPage));
    });
    const shortcutButtons = [...document.querySelectorAll("[data-cockpit-target]")];
    shortcutButtons.forEach((button) => {
      button.classList.remove("active");
      button.removeAttribute("aria-current");
    });
    const shortcutBar = document.querySelector(".cockpit-shortcuts");
    if (shortcutBar) void shortcutBar.offsetWidth;
    shortcutButtons.forEach((button) => {
      const active = button.dataset.cockpitTarget === state.cockpitPage;
      if (active) {
        button.classList.add("active");
        button.setAttribute("aria-current", "page");
      }
    });
    requestAnimationFrame(() => {
      if (focusId) document.getElementById(focusId)?.scrollIntoView({ behavior: "smooth", block: "start" });
      else if (state.dashboard.scroll.has(state.cockpitPage)) {
        window.scrollTo({ top: state.dashboard.scroll.get(state.cockpitPage), behavior: "auto" });
      } else if (!window.matchMedia?.("(max-width: 720px)").matches) window.scrollTo(0, 0);
    });
  }

  function markdown(value) {
    return window.MinikunMarkdown.render(value);
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

  /* Image studio ------------------------------------------------------ */
  function studioValue(selector) {
    return String($(selector)?.value || "").trim();
  }

  function assembledStudioPrompt() {
    return studioValue("#studio-prompt-preview");
  }

  function assembledManualPrompt() {
    const subject = studioValue("#studio-subject");
    if (!subject) return "";
    const parts = [
      "score_9",
      "score_8_up",
      "score_7_up",
      subject
    ];
    const scene = studioValue("#studio-scene");
    const detail = studioValue("#studio-detail");
    if (detail) parts.push(detail);
    if (scene) parts.push(scene);
    if (state.studio.style) parts.push(state.studio.style);
    if (state.studio.light) parts.push(state.studio.light);
    if (state.studio.frame) parts.push(state.studio.frame);
    return parts.map(value => value.replace(/[.!?]+$/g, "").trim()).filter(Boolean).join(", ");
  }

  function studioReadiness() {
    const values = {
      quality: true,
      subject: Boolean(studioValue("#studio-subject")),
      action: Boolean(studioValue("#studio-detail")),
      scene: Boolean(studioValue("#studio-scene")),
      finish: Boolean(state.studio.style && state.studio.light && state.studio.frame)
    };
    const score = 10 + (values.subject ? 30 : 0) + (values.action ? 20 : 0)
      + (values.scene ? 20 : 0) + (values.finish ? 20 : 0);
    return { values, score };
  }

  function studioNumber(selector, fallback) {
    const value = Number($(selector)?.value);
    return Number.isFinite(value) ? value : fallback;
  }

  function studioGenerationSettings() {
    const seedText = studioValue("#studio-seed");
    const negativePrompt = [studioValue("#studio-negative-prompt"), studioValue("#studio-constraints")]
      .filter(Boolean).join(", ");
    return {
      negative_prompt: negativePrompt,
      face_prompts: studioValue("#studio-face-prompts").split(/\n+/).map(value => value.trim()).filter(Boolean),
      width: state.studio.width,
      height: state.studio.height,
      steps: studioNumber("#studio-steps", 40),
      guidance: studioNumber("#studio-guidance", 6.0),
      scheduler: $("#studio-scheduler").value,
      schedule: $("#studio-schedule").value,
      seed: seedText ? Number(seedText) : null
    };
  }

  function updateStudioCanvasMeta() {
    const steps = studioNumber("#studio-steps", 40);
    $("#studio-canvas-meta").textContent = `${state.studio.width} × ${state.studio.height} · ${steps} steps`;
    $("#studio-canvas-stage").style.aspectRatio = `${state.studio.width} / ${state.studio.height}`;
  }

  function selectStudioSize(button) {
    const [width, height] = button.dataset.studioSize.split("x").map(Number);
    state.studio.width = width;
    state.studio.height = height;
    document.querySelectorAll("[data-studio-size]").forEach((node) => {
      node.classList.toggle("selected", node === button);
    });
    updateStudioCanvasMeta();
  }

  function setStudioActiveStep(step) {
    if (["style", "light", "frame"].includes(step)) step = "finish";
    if (step === "detail") step = "action";
    document.querySelectorAll("[data-studio-step]").forEach((node) => {
      node.classList.toggle("active", node.dataset.studioStep === step);
    });
  }

  function updateStudioPrompt() {
    const { values, score } = studioReadiness();
    document.querySelectorAll("[data-studio-step]").forEach((node) => {
      node.classList.toggle("complete", Boolean(values[node.dataset.studioStep]));
    });
    let message;
    if (!values.subject) message = "เริ่มด้วย count + character tags เช่น 1girl, solo";
    else if (!values.action) message = "เพิ่ม action, hands และ expression ให้ตัวละครครับ";
    else if (!values.scene) message = "เพิ่มฉากด้วยคำที่มองเห็น เช่น room, night, rain";
    else if (!values.finish) message = "เลือก style, light และ camera เพื่อปิด prompt ครับ";
    else message = "Pony tags เรียงครบแล้ว กดใช้ tags ชุดนี้ได้เลยครับ";
    $("#studio-coach-copy").textContent = message;
    $("#studio-use-manual").disabled = score < 40;
  }

  function updateStudioOutputState(message = "") {
    const prompt = assembledStudioPrompt();
    const ready = Boolean(prompt);
    const container = $(".assembled-prompt");
    const readiness = $("#studio-readiness");
    container.dataset.state = ready ? "ready" : "empty";
    readiness.querySelector("span").style.width = ready ? "100%" : "0";
    readiness.querySelector("span").style.background = "var(--studio-mint)";
    readiness.querySelector("small").textContent = message || (ready
      ? "พร้อมสร้างภาพ — แก้ tag ในช่องนี้ต่อได้ครับ"
      : "เล่าภาพด้านบน แล้วกดแปลงเป็น Pony prompt");
  }

  async function transformStudioPrompt() {
    const brief = studioValue("#studio-brief");
    if (!brief || state.studio.transforming) {
      if (!brief) {
        $("#studio-brief").focus();
        toast("เล่าภาพที่อยากสร้างให้มินิคุงฟังก่อนครับ", true);
      }
      return;
    }
    state.studio.transforming = true;
    const button = $("#studio-transform-prompt");
    button.disabled = true;
    button.querySelector("span").textContent = "มินิคุงกำลังเรียง tags…";
    $("#studio-transform-status").textContent = "กำลังแปลภาพในหัวเป็นภาษาที่ Pony เข้าใจครับ";
    try {
      const result = await api("/v1/images/studio/prompts/pony", {
        method: "POST", body: JSON.stringify({ brief })
      });
      $("#studio-prompt-preview").value = result.prompt || "";
      updateStudioOutputState("มินิคุงเรียง Pony tags ให้แล้ว ตรวจหรือแก้ได้ก่อนสร้างภาพครับ");
      $("#studio-transform-status").textContent = "รักษารายละเอียดจากที่พี่เล่าไว้ และจัดลำดับจากตัวละครไปถึงกล้องแล้วครับ";
      $("#studio-prompt-preview").focus();
    } catch (error) {
      const message = error.status === 503
        ? "ตอนนี้มินิคุงยังแปลง prompt ไม่ได้ ลองอีกครั้งหรือเปิด Manual Pony ครับ"
        : error.message;
      $("#studio-transform-status").textContent = message;
      toast(message, true);
    } finally {
      state.studio.transforming = false;
      button.disabled = false;
      button.querySelector("span").textContent = "แปลงเป็น Pony prompt";
    }
  }

  function updateStudioGenerateButton() {
    const button = $("#studio-generate");
    if (!button) return;
    const offline = !state.studio.runtimeOnline;
    const busy = state.studio.busy;
    button.disabled = offline || busy;
    button.dataset.state = offline ? "offline" : busy ? "busy" : "ready";
    button.querySelector("span").textContent = busy ? "กำลังสร้างภาพ…" : "สร้างภาพนี้";
    button.querySelector("small").textContent = offline
      ? "เปิด TinyGrad ก่อน แล้วมินิคุงจะลองเชื่อมต่อใหม่"
      : "ส่ง Pony prompt ให้ TinyGrad";
  }

  function renderStudioRuntime(status) {
    const tab = $("#studio-runtime");
    if (!tab) return;
    const online = Boolean(status?.online);
    state.studio.runtimeOnline = online;
    const activeBytes = Number(status?.memory?.active_bytes);
    const budgetBytes = Number(status?.vram_budget_bytes);
    const percent = Number(status?.vram_used_percent);
    const validPercent = Number.isFinite(percent) && percent >= 0;
    const generating = status?.status === "GENERATING";
    const highPressure = online && validPercent && percent >= 95;
    tab.dataset.state = online ? "online" : "offline";
    tab.dataset.pressure = highPressure ? "high" : "normal";
    $("#studio-runtime-status").textContent = !online ? "TinyGrad ยังไม่พร้อม"
      : generating ? "TinyGrad กำลังสร้างภาพ"
      : status.recycle_requested ? "TinyGrad พร้อม · รอ recycle"
        : highPressure ? "TinyGrad พร้อม · VRAM ใกล้เต็ม" : "TinyGrad พร้อมสร้างภาพ";
    $("#studio-runtime-endpoint").textContent = String(status?.endpoint || "http://127.0.0.1:8002/health")
      .replace(/^https?:\/\//, "");
    $("#studio-runtime-vram").textContent = online ? formatBinaryBytes(activeBytes) : "—";
    $("#studio-runtime-budget").textContent = online ? `/ ${formatBinaryBytes(budgetBytes)}` : "/ —";
    $("#studio-runtime-percent").textContent = online && validPercent ? `${percent.toFixed(1)}%` : "—";
    $("#studio-runtime-vram-bar").style.width = online && validPercent
      ? `${Math.min(100, Math.max(0, percent))}%` : "0%";
    const requests = Number(status?.request_count);
    $("#studio-runtime-requests").textContent = online && Number.isFinite(requests) && requests >= 0
      ? requests.toLocaleString("th-TH") : "—";
    const compilerProcesses = Number(status?.compiler_processes);
    const compileWorkers = Number(status?.compile_workers);
    $("#studio-runtime-compiler").textContent = online
      ? `${Number.isFinite(compilerProcesses) ? compilerProcesses : 0}P · ${Number.isFinite(compileWorkers) ? compileWorkers : 0}W`
      : "—";
    const checkedAt = status?.checked_at ? new Date(status.checked_at) : new Date();
    const latency = Number(status?.latency_ms);
    $("#studio-runtime-updated").textContent = online
      ? `${checkedAt.toLocaleTimeString("th-TH", { hour: "2-digit", minute: "2-digit", second: "2-digit" })}${Number.isFinite(latency) ? ` · ${latency}ms` : ""}`
      : "เชื่อมต่อไม่ได้";

    if (online && validPercent) {
      state.studio.runtimeHistory.push(Math.min(100, Math.max(0, percent)));
      state.studio.runtimeHistory = state.studio.runtimeHistory.slice(-30);
    }
    const history = state.studio.runtimeHistory;
    const graphValues = history.length === 1 ? [history[0], history[0]] : history;
    const points = graphValues.map((value, index) => {
      const x = graphValues.length < 2 ? 0 : index * 180 / (graphValues.length - 1);
      const y = 32 - value * 0.29;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    }).join(" ");
    $("#studio-runtime-line").setAttribute("points", points);
    updateStudioGenerateButton();
  }

  async function refreshStudioRuntime() {
    if (state.studio.busy || state.studio.runtimeBusy || document.hidden || $("#studio-view")?.classList.contains("hidden")) return;
    state.studio.runtimeBusy = true;
    try {
      renderStudioRuntime(await api("/v1/images/studio/status"));
    } catch (_) {
      renderStudioRuntime({ online: false, endpoint: "http://127.0.0.1:8002/health", checked_at: new Date().toISOString() });
    } finally {
      state.studio.runtimeBusy = false;
    }
  }

  function updateStudioRuntimePolling(view) {
    if (state.studio.runtimeTimer) {
      clearInterval(state.studio.runtimeTimer);
      state.studio.runtimeTimer = null;
    }
    if (view !== "studio" || document.hidden) return;
    refreshStudioRuntime();
    state.studio.runtimeTimer = setInterval(refreshStudioRuntime, 2_000);
  }

  function selectStudioOption(button) {
    const group = button.dataset.studioOption;
    const wasSelected = button.classList.contains("selected");
    document.querySelectorAll(`[data-studio-option="${group}"]`).forEach((node) => node.classList.remove("selected"));
    state.studio[group] = wasSelected ? "" : button.dataset.value;
    if (!wasSelected) button.classList.add("selected");
    setStudioActiveStep(group);
    updateStudioPrompt();
  }

  function loadStudioExample() {
    $("#studio-brief").value = "หญิงสาวผมดำสั้น สวมเสื้อไหมพรมสีเขียว กำลังประคองขวดแก้วที่มีแสงดาวอยู่ข้างใน มีแมวดำนั่งมองอยู่ข้าง ๆ บนโต๊ะไม้ในหอดูดาวเก่า คืนฝนตก แสงจันทร์สีน้ำเงินตัดกับแสงอุ่นจากโคมไฟ ภาพระยะใกล้แบบ anime key visual ให้ความรู้สึกสงบและมีความหวัง";
    $("#studio-brief").focus();
    $("#studio-transform-status").textContent = "ตัวอย่างพร้อมแล้ว ปรับรายละเอียดได้ก่อนให้มินิคุงแปลงครับ";
    toast("ใส่โจทย์ตัวอย่างให้แล้วครับ กดแปลงเป็น Pony prompt ได้เลย");
  }

  function setStudioCanvasState(canvasState) {
    $("#studio-canvas-stage").dataset.state = canvasState;
    $("#studio-canvas-empty").classList.toggle("hidden", canvasState !== "empty");
    $("#studio-canvas-generating").classList.toggle("hidden", canvasState !== "generating");
    $("#studio-canvas-result").classList.toggle("hidden", canvasState !== "result");
    $("#studio-result-actions").classList.toggle("hidden", canvasState !== "result");
  }

  function showStudioResult(result) {
    const image = $("#studio-result-image");
    image.onload = () => setStudioCanvasState("result");
    image.onerror = () => {
      setStudioCanvasState("empty");
      toast("สร้างภาพสำเร็จ แต่เปิดไฟล์ภาพไม่ได้ครับ", true);
    };
    image.src = result.url;
    image.alt = studioValue("#studio-brief") || studioValue("#studio-subject") || "ภาพที่สร้างโดยมินิคุง";
    const createdAt = result.created_at ? new Date(result.created_at) : new Date();
    $("#studio-result-time").textContent = new Intl.DateTimeFormat("th-TH", {
      hour: "2-digit", minute: "2-digit"
    }).format(createdAt);
    $("#studio-download-result").href = result.url;
    state.studio.result = {
      title: "ภาพที่สร้างใน Minikun Image Studio",
      url: result.url,
      originalUrl: result.url,
      description: result.prompt,
      origin: "generated",
      provider: result.provider || "",
      prompt: result.prompt || "",
      negativePrompt: result.negative_prompt || "",
      seed: result.seed ?? null,
      generationId: result.generation_id || ""
    };
  }

  async function generateStudioImage(event) {
    event.preventDefault();
    const form = $("#studio-form");
    if (!form.reportValidity() || state.studio.busy) return;
    if (!state.studio.runtimeOnline) {
      toast("TinyGrad ยังไม่พร้อม เปิด service แล้วลองใหม่ครับ", true);
      return;
    }
    const prompt = assembledStudioPrompt();
    if (!prompt) {
      $("#studio-brief").focus();
      toast("แปลงหรือวาง Pony prompt ก่อนสร้างภาพครับ", true);
      return;
    }
    state.studio.busy = true;
    updateStudioRuntimePolling("");
    updateStudioGenerateButton();
    setStudioCanvasState("generating");
    try {
      const result = await api("/v1/images/studio/generations", {
        method: "POST", body: JSON.stringify({ prompt, ...studioGenerationSettings() })
      });
      showStudioResult(result);
      toast("TinyGrad สร้างภาพจาก Pony tags ชุดนี้เสร็จแล้วครับ");
    } catch (error) {
      setStudioCanvasState(state.studio.result ? "result" : "empty");
      let message = error.message;
      if (error.status === 404) {
        message = "Image Studio ยังไม่เปิดใช้งาน กรุณาตั้ง MINIKUN_VISUAL_GENERATION_ENABLED=true ครับ";
      } else if (error.status === 503) {
        message = "TinyGrad ยังไม่พร้อมที่ 127.0.0.1:8002 — เปิด sdxl_use.py --serve แล้วลองใหม่ครับ";
      } else if (error.status === 504) {
        message = "TinyGrad ใช้เวลาสร้างภาพเกินกำหนด ตรวจว่า service ยังทำงานอยู่แล้วลองใหม่ครับ";
      } else if (error.status === 502) {
        message = `TinyGrad ตอบกลับผิดพลาด: ${error.message}`;
      }
      toast(message, true);
    } finally {
      state.studio.busy = false;
      updateStudioRuntimePolling("studio");
      updateStudioGenerateButton();
    }
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
    if (contextTokens > 0) values.push(`บริบท ${contextTokens.toLocaleString("th-TH")} โทเคน`);
    const completionTokens = Number(message.usage?.completionTokens);
    if (completionTokens > 0) values.push(`คำตอบ ${completionTokens.toLocaleString("th-TH")} โทเคน`);
    const thinkingMs = Number(message.timing?.firstTokenMs || message.timing?.totalMs);
    if (thinkingMs > 0) values.push(`คิด ${formatDuration(thinkingMs)}`);
    const totalMs = Number(message.timing?.totalMs);
    if (totalMs > thinkingMs) values.push(`รวม ${formatDuration(totalMs)}`);
    if (message.status === "stopped") values.push("หยุดโดยผู้ใช้");
    if (message.status === "failed") values.push("ส่งไม่สำเร็จ");
    if (message.status === "truncated" || isLengthFinishReason(message.finishReason)) {
      values.push("คำตอบยังไม่จบ — ชนเพดาน");
    }
    if (!values.length) return null;
    const meta = element("div", "response-meta");
    values.forEach((value) => meta.append(element("span", "", value)));
    return meta;
  }

  function isLengthFinishReason(value) {
    return ["length", "max_tokens", "max-tokens"].includes(String(value || "").trim().toLowerCase());
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

  async function setMessageFeedback(message, value) {
    if (message.feedback === value) {
      message.feedback = "";
      message.feedbackReason = "";
    } else {
      message.feedback = value;
      message.feedbackReason = value === "down" ? (await requestFeedback()).slice(0, 500) : "";
    }
    saveConversation("", state.currentConversationId, state.chatMessages);
    renderChat();
    try {
      const responseId = message.responseId || message.id || "";
      const query = `conversation_id=${encodeURIComponent(state.currentConversationId)}&message_id=${encodeURIComponent(responseId)}`;
      if (!message.feedback) {
        await api(`/v1/chat/feedback?${query}`, { method: "DELETE" });
      } else {
        await api("/v1/chat/feedback", {
          method: "POST",
          body: JSON.stringify({
            owner_id: state.ownerId,
            conversation_id: state.currentConversationId,
            message_id: responseId,
            rating: message.feedback,
            reason: message.feedbackReason || ""
          })
        });
      }
      toast(message.feedback ? "บันทึก feedback และนำไปปรับการตอบแล้วครับ" : "ยกเลิก feedback แล้วครับ");
    } catch (error) {
      toast(`เก็บ feedback ไว้ในเครื่องแล้ว · ${error.message}`, true);
    }
  }

  function renderLiveProgress(message) {
    const progress = element("div", "live-progress");
    const label = element("span", "live-progress-label",
      (message.progress || "กำลังทำความเข้าใจคำถาม…").replace(/…$/, ""));
    const pulse = element("span", "live-progress-pulse");
    pulse.setAttribute("aria-hidden", "true");
    pulse.innerHTML = "<i></i><i></i><i></i>";
    progress.append(label, pulse);
    return progress;
  }

  function renderVisualGallery(attachments = []) {
    if (!attachments.length) return null;
    const visuals = attachments.map(normalizeVisual).filter((visual) => visual.url);
    if (!visuals.length) return null;
    const gallery = element("section", "message-visual-gallery");
    const galleryHeading = element("div", "message-visual-heading");
    const generatedCount = visuals.filter((visual) => visual.origin === "generated").length;
    const galleryMeta = generatedCount === visuals.length
      ? `${visuals.length} ภาพ · สร้างโดย AI`
      : generatedCount > 0
        ? `${visuals.length} ภาพ · สร้างใหม่ ${generatedCount} ภาพ`
        : `${visuals.length} ภาพ · เปิดดูต้นทางได้`;
    galleryHeading.append(
      element("strong", "", generatedCount === visuals.length ? "ภาพที่มินิคุงสร้าง" : "รูปประกอบคำตอบ"),
      element("span", "", galleryMeta)
    );
    gallery.append(galleryHeading);
    const images = element("div", "message-image-grid");
    for (const visual of visuals) {
      const card = element("figure", "visual-card");
      card.classList.toggle("is-generated", visual.origin === "generated");
      const open = element("button", "visual-card-image");
      open.type = "button";
      open.setAttribute("aria-label", `เปิดภาพ ${visual.title}`);
      open.addEventListener("click", () => openVisualLightbox(visual));
      const image = element("img");
      image.src = visual.url || proxyImageUrl(visual.originalUrl);
      image.alt = visual.title;
      image.loading = "lazy";
      image.addEventListener("error", () => {
        if (visual.thumbnailUrl && image.dataset.thumbnailFallback !== "true") {
          image.dataset.thumbnailFallback = "true";
          image.src = proxyImageUrl(visual.thumbnailUrl);
          return;
        }
        image.classList.add("visual-broken");
        image.alt = "โหลดภาพนี้ไม่สำเร็จ";
      });
      open.append(image, element("span", "visual-origin", visualOriginLabel(visual.origin)));
      const copy = element("figcaption", "visual-card-copy");
      copy.append(element("strong", "", visual.title), element("small", "", visualMetadata(visual)));
      const generationMetadata = generationMetadataPanel(visual, true);
      if (generationMetadata) copy.append(generationMetadata);
      const sourceUrl = safeExternalUrl(visual.sourceUrl || visual.originalUrl);
      if (sourceUrl) {
        const source = element("a", "visual-card-source", `เปิดต้นทาง ${visualHost(visual)} ↗`);
        source.href = sourceUrl; source.target = "_blank"; source.rel = "noopener noreferrer";
        copy.append(source);
      }
      const actions = element("div", "visual-card-actions");
      const ask = element("button", "", "ถามต่อ");
      ask.type = "button"; ask.addEventListener("click", () => attachVisualReference(visual));
      const similar = element("button", "", "รูปคล้ายกัน");
      similar.type = "button"; similar.addEventListener("click", () => findSimilarVisual(visual));
      const save = element("button", "", "เก็บ reference");
      save.type = "button"; save.addEventListener("click", () => saveVisualReference(visual).catch(error => toast(error.message, true)));
      actions.append(ask, similar, save); copy.append(actions); card.append(open, copy); images.append(card);
    }
    gallery.append(images);
    return gallery;
  }

  function renderMessage(message, live = false) {
    const row = element("article", `message ${message.role}`);
    row.dataset.messageId = message.id || "";
    const avatar = message.role === "assistant"
      ? Object.assign(element("img", "message-avatar"), { src: "/cockpit/minikun-avatar.jpg", alt: "มินิคุง" })
      : null;
    const body = element("div", "message-body");
    if (message.files?.length) {
      const files = element("div", "message-files");
      for (const file of message.files) files.append(element("span", "message-file", file));
      body.append(files);
    }
    const content = element("div", "message-content");
    content.innerHTML = markdown(message.content);
    body.append(content);
    if (live) body.append(renderLiveProgress(message));
    const visualGallery = renderVisualGallery(message.attachments);
    if (visualGallery) body.append(visualGallery);
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
        if (message.status === "truncated" || isLengthFinishReason(message.finishReason)) {
          const continueButton = element("button", "", "ตอบต่อ");
          continueButton.type = "button";
          continueButton.addEventListener("click", () => continueTruncatedMessage(index));
          tools.append(continueButton);
        }
        const regenerate = element("button", "", message.status === "failed" ? "ลองอีกครั้ง" : "ตอบใหม่");
        regenerate.type = "button";
        regenerate.addEventListener("click", () => regenerateMessage(index));
        tools.append(regenerate);
        for (const [value, label] of [["up", "มีประโยชน์"], ["down", "ควรปรับ"]]) {
          const feedback = element("button", message.feedback === value ? "selected" : "", label);
          feedback.type = "button";
          feedback.setAttribute("aria-label", value === "up" ? "คำตอบมีประโยชน์" : "คำตอบควรปรับปรุง");
          feedback.addEventListener("click", () => setMessageFeedback(message, value));
          tools.append(feedback);
        }
      }
      body.append(tools);
    }
    if (avatar) row.append(avatar);
    row.append(body);
    return row;
  }

  function renderChat() {
    const scroll = $("#messages-scroll");
    const distanceFromLatest = scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight;
    const conversationChanged = scroll.dataset.conversationId !== state.currentConversationId;
    const shouldStick = conversationChanged || distanceFromLatest < 96;
    const messages = $("#messages");
    messages.replaceChildren();
    $("#chat-welcome").classList.toggle("hidden", state.chatMessages.length > 0);
    for (const message of state.chatMessages) messages.append(renderMessage(message));
    const pending = state.pendingChats.get(state.currentConversationId);
    if (pending && !state.chatMessages.includes(pending.assistant)) {
      messages.append(renderMessage(pending.assistant, true));
    }
    scroll.dataset.conversationId = state.currentConversationId;
    requestAnimationFrame(() => {
      if (!state.chatMessages.length) {
        scroll.scrollTop = 0;
        return;
      }
      if (shouldStick) scrollToLatest();
    });
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
    setChatBusy(Boolean(pending), pending?.status || (state.chatMessages.at(-1)?.status === "failed" ? "เชื่อมต่อไม่สำเร็จ" : "พร้อมช่วยพี่สาว"));
  }

  function updatePendingProgress(task, label) {
    const changed = task.status !== label;
    task.status = label;
    task.assistant.progress = label;
    if (state.currentConversationId !== task.conversationId) return;
    const row = document.querySelector(`[data-message-id="${task.assistant.id}"]`);
    const labelNode = row?.querySelector(".live-progress-label");
    if (labelNode) labelNode.textContent = label.replace(/…$/, "");
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
      updatePendingProgress(task, task.assistant.content ? task.status : stage(elapsed));
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

  async function saveAttachmentAsset(attachment) {
    return globalThis.MinikunChatAssets.save(attachment);
  }

  async function loadAttachmentAsset(assetId) {
    return globalThis.MinikunChatAssets.load(assetId);
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
        image.src = attachment.data || attachment.previewUrl || proxyImageUrl(attachment.remoteUrl);
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
    if (!images.length) return combined;
    return [
      { type: "text", text: combined || "ช่วยดูภาพที่แนบมานี้ให้หน่อยครับ" },
      ...images.map((image) => ({
        type: "image_url", image_url: { url: image.data || image.remoteUrl, detail: "auto" }
      }))
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
      const remoteUrl = attachment.remoteUrl || attachment.originalUrl || attachment.original_url;
      if (attachment.kind === "image" && remoteUrl) {
        hydrated.push({
          kind: "image", name: attachment.title || attachment.name || "Visual reference",
          type: attachment.type || "image/remote", remoteUrl,
          previewUrl: attachment.url || attachment.previewUrl || proxyImageUrl(remoteUrl),
          sourceUrl: attachment.sourceUrl || attachment.source_url || "",
          origin: attachment.origin || "web"
        });
        continue;
      }
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

  function requiresDurableBackground(prompt) {
    return /(deep\s*research|วิจัยเชิงลึก|ค้นคว้า(?:แบบ)?ละเอียด|(?:สร้าง|วาด|เจน|เจเนอเรต|ทำ|ออกแบบ|gen(?:erate)?|create|draw|make|design).{0,40}(?:รูป|ภาพ|images?|pictures?|illustrations?|artwork)|(?:ช่วย(?:จด|จำ|บันทึก)|ฝาก(?:จด|จำ|บันทึก)|อย่าลืม|เพิ่ม|สร้าง|บันทึก|แก้ไข|อัปเดต|ลบ|ย้าย|ส่ง|ตั้ง|รัน|เปิด|ตรวจ|เช็ก|เช็ค|ดำเนินการ|create|add|save|update|delete|remove|move|send|schedule|run|open|check|execute).{0,40}(?:งาน|เตือน|ปฏิทิน|เป้าหมาย|ไฟล์|โฟลเดอร์|คอมพิวเตอร์|เซิร์ฟเวอร์|โฮมแล็บ|ระบบ|เว็บ|เว็บไซต์|ลิงก์|ความจำ|ข้อความ|task|reminder|calendar|goal|file|folder|computer|server|homelab|system|website|url|link|memory|message|email|portfolio|investment)|todo\s*:|ทำงานเบื้องหลัง|background\s+(?:job|task))/i
      .test(String(prompt || ""));
  }

  function shouldUseBackgroundChat(prompt, backgroundJobId = "") {
    return Boolean(backgroundJobId) || requiresDurableBackground(prompt);
  }

  function updateStreamingMessage(task) {
    if (state.currentConversationId !== task.conversationId) return;
    const row = document.querySelector(`[data-message-id="${task.assistant.id}"]`);
    const content = row?.querySelector(".message-content");
    if (content) content.innerHTML = markdown(task.assistant.content);
    scrollToLatest();
  }

  async function streamChatCompletion(task, requestBody) {
    const response = await fetch(Core.withOwner("/v1/chat/completions", state.ownerId), {
      method: "POST",
      headers: { ...headers(true), "X-Conversation-Id": task.conversationId },
      signal: task.controller.signal,
      body: JSON.stringify({ ...requestBody, stream: true })
    });
    if (!response.ok) {
      let message = `เชื่อมต่อไม่สำเร็จ (${response.status})`;
      try {
        const body = await response.json();
        message = body.error?.message || body.message || message;
      } catch (_) { /* keep status message */ }
      throw new Error(message);
    }
    const reader = response.body?.getReader();
    if (!reader) throw new Error("เบราว์เซอร์นี้ยังไม่รองรับคำตอบแบบ streaming");
    const decoder = new TextDecoder();
    let buffer = "";
    const consume = (line) => {
      const data = line.startsWith("data:") ? line.slice(5).trimStart() : line.trim();
      if (!data || data === "[DONE]" || !data.startsWith("{")) return;
      const chunk = JSON.parse(data);
      if (chunk.id) task.assistant.responseId = String(chunk.id);
      if (chunk.image_status === "running") updatePendingProgress(task, "กำลังสร้างภาพประกอบ…");
      const choice = chunk.choices?.[0] || {};
      const delta = choice.delta || {};
      if (delta.content) {
        if (!task.assistant.timing.firstTokenMs) {
          task.assistant.timing.firstTokenMs = Date.now() - task.assistant.timing.startedAt;
          updatePendingProgress(task, "กำลังตอบ…");
        }
        task.assistant.content += delta.content;
        updateStreamingMessage(task);
      }
      task.assistant.finishReason = choice.finish_reason || task.assistant.finishReason;
      if (chunk.usage) {
        task.assistant.usage = {
          promptTokens: Number(chunk.usage.prompt_tokens) || 0,
          completionTokens: Number(chunk.usage.completion_tokens) || 0,
          totalTokens: Number(chunk.usage.total_tokens) || 0
        };
      }
      const visuals = [...(chunk.attachments || []), ...(delta.images || [])]
        .map(normalizeVisual).filter((image) => image.url);
      for (const visual of visuals) {
        if (!task.assistant.attachments.some((item) => item.url === visual.url)) {
          task.assistant.attachments.push(visual);
        }
      }
    };
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
      const lines = buffer.split(/\r?\n/);
      buffer = lines.pop() || "";
      for (const line of lines) consume(line);
      if (done) break;
    }
    if (buffer.trim()) consume(buffer);
  }

  async function runChatTurn({ conversationId, messages, userMessage, currentContent, seed, backgroundJobId = "" }) {
    const assistant = {
      id: uniqueId("message-"), role: "assistant", content: "", attachments: [], createdAt: Date.now(),
      parentId: userMessage.id, branchId: userMessage.branchId || conversationId, status: "generating",
      finishReason: "",
      timing: { startedAt: Date.now(), firstTokenMs: 0, totalMs: 0 }, sources: [], feedback: ""
    };
    if (state.currentConversationId === conversationId) {
      $("#chat-welcome").classList.add("hidden");
      renderChat();
      $("#messages").append(renderMessage(assistant, true));
      scrollToLatest();
    }
    saveConversation(seed || userMessage.content, conversationId, messages);
    const task = {
      controller: new AbortController(), assistant, conversationId, jobId: backgroundJobId,
      status: "กำลังทำความเข้าใจคำถาม…", progressTimer: null
    };
    state.pendingChats.set(conversationId, task);
    startPendingProgress(task, userMessage.content);
    renderConversationList();
    syncChatState();
    try {
      const requestBody = {
        model: state.model,
        messages: await promptMessages(messages, userMessage, currentContent),
        conversation_id: conversationId,
        owner_id: state.ownerId,
        device_location: state.currentLocation ? { ...state.currentLocation } : null
      };
      const useBackground = shouldUseBackgroundChat(userMessage.content, backgroundJobId);
      let backgroundResult = null;
      if (!useBackground) {
        await streamChatCompletion(task, requestBody);
      } else if (!task.jobId) {
        const started = await api("/v1/chat/background", {
          method: "POST",
          headers: {
            "X-Conversation-Id": conversationId,
            "X-Idempotency-Key": String(userMessage.id || uniqueId("background-"))
          },
          signal: task.controller.signal,
          body: JSON.stringify({ ...requestBody, stream: false })
        });
        task.jobId = started.id;
        userMessage.backgroundJobId = started.id;
        saveConversation(seed || userMessage.content, conversationId, messages);
      }
      if (useBackground) {
        let result;
        let lastRenderedCompletion = "";
        while (!task.controller.signal.aborted) {
          result = await api(`/v1/chat/background/${encodeURIComponent(task.jobId)}`, {
            signal: task.controller.signal
          });
          const imageStatus = String(result.imageStatus || result.image_status || "not_requested").toLowerCase();
          if (result.response) {
            const completion = result.response;
            const choice = completion.choices?.[0] || {};
            const content = String(choice.message?.content || "");
            const attachments = (completion.attachments || []).map(normalizeVisual).filter((image) => image.url);
            const signature = `${content}|${attachments.map((image) => image.url).join(",")}|${imageStatus}`;
            assistant.responseId = String(completion.id || assistant.responseId || "");
            assistant.content = content;
            assistant.finishReason = String(choice.finish_reason || "");
            assistant.attachments = attachments;
            if (completion.usage) {
              assistant.usage = {
                promptTokens: Number(completion.usage.prompt_tokens) || 0,
                completionTokens: Number(completion.usage.completion_tokens) || 0,
                totalTokens: Number(completion.usage.total_tokens) || 0
              };
            }
            if (signature !== lastRenderedCompletion) {
              lastRenderedCompletion = signature;
              if (imageStatus === "queued" || imageStatus === "running") {
                updatePendingProgress(task, "กำลังสร้างภาพประกอบ…");
              }
              if (state.currentConversationId === conversationId) renderChat();
            }
          }
          const imageFinished = !["queued", "running"].includes(imageStatus);
          const chatStatus = String(result.status || "").toLowerCase();
          const chatFinished = !["queued", "running"].includes(chatStatus);
          if (chatFinished && imageFinished) break;
          await new Promise((resolve) => window.setTimeout(resolve, 1500));
        }
        if (task.controller.signal.aborted || result?.status === "cancelled") {
          throw new DOMException("ยกเลิกคำตอบแล้ว", "AbortError");
        }
        if (result?.status !== "completed") throw new Error(result?.error || "มินิคุงตอบไม่สำเร็จ");
        backgroundResult = result;
      }
      assistant.timing.totalMs = Date.now() - assistant.timing.startedAt;
      if (!assistant.content && !assistant.attachments.length) assistant.content = "ได้รับข้อความแล้วครับ แต่ยังไม่มีคำตอบกลับมา";
      assistant.status = isLengthFinishReason(assistant.finishReason) ? "truncated" : "complete";
      assistant.sources = extractSources(assistant.content);
      userMessage.backgroundJobId = "";
      messages.push(assistant);
      saveConversation(seed || userMessage.content, conversationId, messages);
      const finalImageStatus = String(backgroundResult?.imageStatus || backgroundResult?.image_status || "not_requested").toLowerCase();
      if (task.jobId && !["queued", "running"].includes(finalImageStatus)) {
        api(`/v1/chat/background/${encodeURIComponent(task.jobId)}`, { method: "DELETE" }).catch(() => {});
      }
      if (state.currentConversationId === conversationId) {
        renderChat();
        if (state.voiceOutput && assistant.content) speak(assistant.content);
      }
    } catch (error) {
      if (error.name === "AbortError") {
        userMessage.backgroundJobId = "";
        assistant.timing.totalMs = Date.now() - assistant.timing.startedAt;
        if (assistant.content) {
          assistant.content += "\n\n_หยุดคำตอบแล้ว_";
          assistant.status = "stopped";
          messages.push(assistant);
          saveConversation(seed || userMessage.content, conversationId, messages);
        } else saveConversation(seed || userMessage.content, conversationId, messages);
        if (state.currentConversationId === conversationId) renderChat();
      } else {
        userMessage.backgroundJobId = "";
        assistant.timing.totalMs = Date.now() - assistant.timing.startedAt;
        assistant.content = `ขออภัยครับ ตอนนี้เชื่อมต่อไม่สำเร็จ\n\n${error.message}`;
        assistant.localOnly = true;
        assistant.status = "failed";
        messages.push(assistant);
        saveConversation(seed || userMessage.content, conversationId, messages);
        if (task.jobId) api(`/v1/chat/background/${encodeURIComponent(task.jobId)}`, { method: "DELETE" }).catch(() => {});
        if (state.currentConversationId === conversationId) renderChat();
        toast(error.message, true);
      }
    } finally {
      window.clearInterval(task.progressTimer);
      if (state.pendingChats.get(conversationId) === task) state.pendingChats.delete(conversationId);
      renderConversationList();
      syncChatState();
      if (!state.pendingChats.size && state.sync.remoteDirty && !assistant.localOnly) {
        const synced = await pushConversationSync(conversationId);
        if (synced) await refreshSyncedConversations();
      }
      if (state.currentConversationId === conversationId && !window.matchMedia("(pointer: coarse)").matches) {
        $("#chat-composer").focus();
      }
    }
  }

  function resumeBackgroundChats() {
    for (const conversation of state.conversations) {
      if (state.pendingChats.has(conversation.id)) continue;
      const userMessage = [...(conversation.messages || [])].reverse().find((message) =>
        message.role === "user" && message.backgroundJobId
        && !(conversation.messages || []).some((candidate) =>
          candidate.role === "assistant" && candidate.parentId === message.id));
      if (!userMessage) continue;
      runChatTurn({
        conversationId: conversation.id,
        messages: conversation.messages,
        userMessage,
        seed: conversation.title,
        backgroundJobId: userMessage.backgroundJobId
      });
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
        title: item.name, url: item.kind === "image" ? (item.previewUrl || item.data) : "",
        originalUrl: item.remoteUrl || "", sourceUrl: item.sourceUrl || "", origin: item.origin || "user",
        assetId: item.assetId, kind: item.kind, type: item.type
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
    if (!branch && state.pendingChats.has(conversationId)) {
      toast("รอให้มินิคุงตอบเสร็จก่อนครับ");
      return;
    }
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
      source.splice(userIndex, source.length - userIndex, userMessage);
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

  async function continueTruncatedMessage(index) {
    const source = state.chatMessages;
    const previous = source[index];
    const conversationId = state.currentConversationId;
    if (!previous || previous.role !== "assistant" || state.pendingChats.has(conversationId)) return;
    const userMessage = {
      id: uniqueId("message-"), role: "user",
      content: "ตอบต่อจากคำตอบก่อนหน้าให้จบ โดยไม่ทวนเนื้อหาที่ตอบไปแล้ว",
      branchId: conversationId, parentId: previous.id, status: "complete", createdAt: Date.now()
    };
    source.push(userMessage);
    renderChat();
    await runChatTurn({
      conversationId, messages: source, userMessage,
      currentContent: userMessage.content, seed: userMessage.content
    });
  }

  async function editUserMessage(index) {
    const message = state.chatMessages[index];
    if (!message) return;
    const edited = await requestEditedMessage(message.content || "");
    if (edited === null || !edited.trim() || edited.trim() === message.content.trim()) return;
    await rerunFromUser(index, false, edited.trim());
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
      return Promise.reject(new Error("เบราว์เซอร์บนอุปกรณ์นี้ยังไม่มีเสียงอ่าน"));
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
    return Core.wavBlob(chunks, sourceRate);
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
    // Barge-in: starting a voice turn immediately stops Mini-kun's current speech.
    stopSpeech();
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
    $("#sync-state").querySelector(".status-dot").style.background = ok ? "var(--green)" : "var(--danger)";
  }

  function renderStatus(status, decisionCount = 0) {
    if (!status) {
      for (const id of ["#due-count", "#goal-count", "#waiting-count"]) $(id).textContent = "—";
      $("#today").dataset.state = "unavailable";
      $("#attention-title").textContent = "ยังโหลดภาพรวมวันนี้ไม่ได้ครับ";
      $("#attention-copy").textContent = "ลองรีเฟรชอีกครั้งเพื่อดูงานและเรื่องที่รอยืนยันครับ";
      return;
    }
    const due = status.due_tasks ?? "—";
    const goals = status.open_goals ?? "—";
    const waiting = (status.agent_waiting_confirmation || 0) + decisionCount;
    $("#due-count").textContent = due;
    $("#goal-count").textContent = goals;
    $("#waiting-count").textContent = waiting;
    $("#today").dataset.state = status.attention_required || waiting > 0 ? "attention" : "calm";
    $("#attention-title").textContent = status.attention_required || waiting > 0
      ? "มีเรื่องที่ควรดูด้วยกันก่อนครับ"
      : "วันนี้ยังเดินหน้าได้อย่างสบาย ๆ ครับ";
    $("#attention-copy").textContent = status.attention_required
      ? "มินิคุงเรียงเรื่องที่ถึงเวลาและก้าวที่เล็กที่สุดไว้ให้ด้านล่างแล้วครับ"
      : waiting > 0
        ? "มีเรื่องที่มินิคุงเตรียมไว้และรอพี่สาวยืนยันก่อนลงมือครับ"
        : "ไม่มีเรื่องเร่งด่วน เลือกหนึ่งก้าวที่อยากขยับต่อได้เลยครับ";
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
    $("#timeline").classList.toggle("is-empty", items.length === 0);
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

  function renderConversationThreads(items = []) {
    const list = $("#conversation-thread-list");
    list.replaceChildren();
    $("#conversation-thread-count").textContent = items.length;
    $("#conversation-thread-empty").classList.toggle("hidden", items.length > 0);
    $("#conversation-threads").classList.toggle("is-empty", items.length === 0);
    for (const thread of items.slice(0, 6)) {
      const row = element("div", "inbox-item");
      const copy = element("div");
      copy.append(element("strong", "", thread.topic));
      const detail = thread.checkInAt
        ? `นัดถามอีกครั้ง ${relativeTime(thread.checkInAt)}`
        : (thread.unresolvedQuestion || thread.summary || "เรื่องที่กำลังติดตาม");
      copy.append(element("small", "", detail));
      const controls = element("div", "item-controls");
      if (thread.lastCheckInAt) {
        for (const [feedback, label] of [
          ["HELPFUL", "ช่วยได้"], ["NOT_NOW", "ไว้ทีหลัง"],
          ["WRONG_CONTEXT", "ไม่ตรงจังหวะ"], ["STOP_THIS_TOPIC", "หยุดตาม"]
        ]) {
          const button = element("button", thread.lastCheckInFeedback === feedback ? "selected" : "", label);
          button.type = "button";
          button.addEventListener("click", async () => {
            try {
              await api(`/v1/personal/conversation-threads/${thread.id}/feedback`, {
                method: "POST",
                body: JSON.stringify({ owner_id: state.ownerId, feedback })
              });
              toast(feedback === "NOT_NOW" ? "ไว้ถามใหม่วันหลังนะครับ" : "มินิคุงรับฟีดแบ็กแล้วครับ");
              loadDashboard();
            } catch (error) { toast(error.message, true); }
          });
          controls.append(button);
        }
      }
      const resolve = element("button", "", "จบเรื่องนี้");
      resolve.type = "button";
      resolve.addEventListener("click", async () => {
        try {
          await api(`/v1/personal/conversation-threads/${thread.id}`, { method: "DELETE" });
          toast("ปิดเรื่องค้างให้แล้วครับ");
          loadDashboard();
        } catch (error) { toast(error.message, true); }
      });
      controls.append(resolve);
      row.append(copy, controls);
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
    const active = sorted.filter((item) => activeStatuses.has(item.status));
    const history = sorted.filter((item) => !activeStatuses.has(item.status));
    const activeCount = active.length;
    const showingHistory = activeCount === 0 && history.length > 0;
    const visible = (activeCount ? active : history).slice(0, 5);
    $("#task-center").dataset.state = showingHistory ? "history" : activeCount ? "active" : "empty";
    $("#task-center-eyebrow").textContent = activeCount ? "งานที่กำลังเดิน" : showingHistory ? "ประวัติงานล่าสุด" : "งานที่กำลังเดิน";
    $("#task-center-title").textContent = activeCount ? "งานที่มินิคุงกำลังดูแล" : showingHistory ? "งานล่าสุด" : "งานของมินิคุง";
    $("#agent-run-count").textContent = activeCount ? `${activeCount} กำลังดูแล` : history.length ? `${Math.min(history.length, 5)} งานล่าสุด` : "0";
    $("#agent-run-empty").classList.toggle("hidden", visible.length > 0);
    $("#agent-run-empty").textContent = items.length ? "ตอนนี้ไม่มีงานที่กำลังทำอยู่ครับ" : "ยังไม่มีงานเบื้องหลังครับ";
    const list = $("#agent-run-list");
    list.replaceChildren();
    for (const run of visible) {
      const row = element("article", `agent-run status-${String(run.status || "").toLowerCase()}`);
      const heading = element("div", "agent-run-heading");
      heading.append(element("strong", "", run.objective || "งานของมินิคุง"));
      heading.append(element("span", "run-status", statusLabels[run.status] || run.status));
      const currentStep = Math.max(0, Number(run.currentStep) || 0);
      const maxSteps = Math.max(1, Number(run.maxSteps) || 1);
      const progress = Math.max(0, Math.min(100, Math.round((currentStep / maxSteps) * 100)));
      const detail = element("div", "agent-run-detail");
      detail.append(element("span", "", run.status === "COMPLETED" ? "เสร็จครบแล้ว" : `${currentStep}/${maxSteps} ขั้นตอน`));
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

    const dependencyLabels = {
      application: "Minikun", postgres: "Database", redis: "Redis",
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
    $("#health-updated").textContent = new Date().toLocaleTimeString("th-TH", {
      hour: "2-digit", minute: "2-digit", second: "2-digit"
    });
  }

  function renderEvalLab(baseline, quality) {
    const baselineAvailable = Boolean(baseline && typeof baseline === "object");
    const qualityAvailable = Boolean(quality && typeof quality === "object");
    const score = Number(baseline?.scorePercent);
    const badge = $("#eval-baseline-score");
    badge.textContent = Number.isFinite(score) ? `${Math.round(score)}% ผ่าน` : "อ่านไม่ได้";
    badge.dataset.state = !baselineAvailable ? "unknown" : score === 100 ? "up" : "warning";
    $("#eval-baseline-passed").textContent = baselineAvailable ? `${baseline.passed}/${baseline.total}` : "—";
    const approval = Number(quality?.approvalPercent);
    $("#eval-approval").textContent = qualityAvailable && Number.isFinite(approval) ? `${Math.round(approval)}%` : "—";
    $("#eval-feedback-total").textContent = qualityAvailable ? String(quality.feedbackTotal ?? 0) : "—";
    $("#eval-trace-matched").textContent = qualityAvailable ? String(quality.traceMatched ?? 0) : "—";

    const signals = Array.isArray(quality?.shadowSignals) ? quality.shadowSignals : [];
    const list = $("#eval-shadow-list");
    list.replaceChildren();
    for (const signal of signals) {
      const route = String(signal.routeAndCategory || "unknown");
      const [intent, execution, category] = route.split(":");
      const row = element("div", "health-dependency");
      row.dataset.state = "down";
      row.append(element("span", "health-dot"));
      const copy = element("div");
      copy.append(element("strong", "", `${intent || "unknown"} · ${execution || "unknown"}`));
      copy.append(element("small", "", (category || "routing signal").replaceAll("_", " ")));
      row.append(copy, element("span", "health-latency", `${signal.occurrences ?? 0} ครั้ง`));
      list.append(row);
    }
    list.classList.toggle("hidden", !signals.length);
    $("#eval-shadow-empty").classList.toggle("hidden", Boolean(signals.length));
  }

  function activateQuickAction(action) {
    switch (action) {
      case "image":
        showView("chat", "", { updateHistory: true });
        $("#file-input").click();
        break;
      case "idea":
        showView("cockpit", "inbox", { updateHistory: true });
        requestAnimationFrame(() => {
          $("#capture-input")?.focus();
        });
        break;
      case "reminder": {
        showView("chat", "", { updateHistory: true });
        const composer = $("#chat-composer");
        composer.value = "ช่วยสร้าง Reminder ให้ฉัน: ";
        autoGrowComposer();
        composer.focus();
        break;
      }
      case "location":
        showView("cockpit", "permission-center", { updateHistory: true });
        requestCurrentLocation();
        break;
      case "voice":
        showView("chat", "", { updateHistory: true });
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

  function dateTime(value) {
    if (!value) return "ยังไม่กำหนด";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "ยังไม่กำหนด";
    return new Intl.DateTimeFormat("th-TH", {
      day: "numeric", month: "short", hour: "2-digit", minute: "2-digit"
    }).format(date);
  }

  function percentage(value) {
    return `${Math.round((Number(value) || 0) * 100)}%`;
  }

  function renderLearning(topics = [], runs = [], claims = []) {
    const activeTopics = topics.filter((topic) => topic.status === "ACTIVE");
    const published = claims.filter((claim) => claim.status === "PUBLISHED");
    const reviewable = claims.filter((claim) => ["CANDIDATE", "DISPUTED", "STALE"].includes(claim.status));
    const next = activeTopics.map((topic) => topic.nextRunAt).filter(Boolean)
      .sort((left, right) => new Date(left).getTime() - new Date(right).getTime())[0];
    $("#learning-active-count").textContent = activeTopics.length.toLocaleString("th-TH");
    $("#learning-published-count").textContent = published.length.toLocaleString("th-TH");
    $("#learning-candidate-count").textContent = reviewable.length.toLocaleString("th-TH");
    $("#learning-next-run").textContent = next ? relativeTime(next) : "สั่งเอง";

    $("#learning-topic-count").textContent = `${topics.length.toLocaleString("th-TH")} หัวข้อ`;
    $("#learning-topic-empty").classList.toggle("hidden", topics.length > 0);
    const topicList = $("#learning-topic-list");
    topicList.replaceChildren();
    const refreshLabels = { HOURLY: "ทุกชั่วโมง", DAILY: "ทุกวัน", WEEKLY: "ทุกสัปดาห์", MONTHLY: "ทุกเดือน", MANUAL: "สั่งเอง" };
    const policyLabels = { OFFICIAL_ONLY: "เฉพาะแหล่งทางการ", OFFICIAL_FIRST: "แหล่งทางการก่อน", BALANCED: "หลายแหล่งสมดุล" };
    for (const topic of topics) {
      const card = element("article", "learning-topic-card");
      const heading = element("div", "learning-topic-heading");
      const copy = element("div");
      copy.append(element("strong", "", topic.name), element("p", "", topic.objective));
      const status = element("span", "learning-status", topic.status === "ACTIVE" ? "กำลังเรียน" : "พักไว้");
      status.dataset.state = String(topic.status || "").toLowerCase();
      heading.append(copy, status);
      const meta = element("div", "learning-topic-meta");
      meta.append(
        element("span", "", refreshLabels[topic.refreshPolicy] || topic.refreshPolicy),
        element("span", "", policyLabels[topic.sourcePolicy] || topic.sourcePolicy),
        element("span", "", `สำคัญ ${topic.priority}`),
        element("span", "", topic.nextRunAt ? `รอบถัดไป ${dateTime(topic.nextRunAt)}` : "ไม่มีรอบอัตโนมัติ")
      );
      const domains = element("div", "trusted-domain-list");
      for (const domain of topic.trustedDomains || []) domains.append(element("span", "", domain));
      if (!(topic.trustedDomains || []).length) domains.append(element("span", "muted-domain", "ยังไม่จำกัดเว็บไซต์"));
      const actions = element("div", "learning-topic-actions");
      const run = element("button", "primary-button", state.learningBusy.has(topic.id) ? "กำลังเรียน…" : "เรียนตอนนี้");
      run.type = "button";
      run.disabled = state.learningBusy.has(topic.id);
      run.addEventListener("click", () => runLearningTopic(topic, run));
      const toggle = element("button", "secondary-button", topic.status === "ACTIVE" ? "พักตาราง" : "เปิดตาราง");
      toggle.type = "button";
      toggle.addEventListener("click", () => setLearningTopicStatus(topic, toggle));
      actions.append(run, toggle);
      card.append(heading, meta, domains, actions);
      topicList.append(card);
    }

    $("#learning-review-count").textContent = reviewable.length.toLocaleString("th-TH");
    $("#learning-claim-empty").classList.toggle("hidden", reviewable.length > 0);
    const claimList = $("#learning-claim-list");
    claimList.replaceChildren();
    for (const claim of reviewable.slice(0, 15)) {
      const row = element("article", "learning-claim");
      const copy = element("div", "learning-claim-copy");
      const meta = element("div", "learning-claim-meta");
      meta.append(element("span", "", claim.topicName), element("small", "", `มั่นใจ ${percentage(claim.confidence)}`));
      copy.append(meta, element("p", "", claim.text));
      if (claim.verificationReason) copy.append(element("small", "claim-reason", claim.verificationReason));
      const citations = element("div", "claim-citations");
      for (const url of (claim.evidenceUrls || []).slice(0, 3)) {
        const safe = safeExternalUrl(url);
        if (!safe) continue;
        const link = element("a", "", new URL(safe).hostname.replace(/^www\./, ""));
        link.href = safe; link.target = "_blank"; link.rel = "noopener noreferrer";
        citations.append(link);
      }
      if (citations.childElementCount) copy.append(citations);
      const actions = element("div", "claim-review-actions");
      const publish = element("button", "primary-button", "ยืนยันให้ใช้");
      publish.type = "button";
      publish.addEventListener("click", () => reviewLearningClaim(claim, "PUBLISHED", publish));
      const retract = element("button", "secondary-button", "กันออก");
      retract.type = "button";
      retract.addEventListener("click", () => reviewLearningClaim(claim, "RETRACTED", retract));
      actions.append(publish, retract);
      row.append(copy, actions);
      claimList.append(row);
    }

    $("#learning-run-count").textContent = `${runs.length.toLocaleString("th-TH")} รอบ`;
    $("#learning-run-empty").classList.toggle("hidden", runs.length > 0);
    const runList = $("#learning-run-list");
    runList.replaceChildren();
    const topicNames = new Map(topics.map((topic) => [topic.id, topic.name]));
    const runStatusLabels = { RUNNING: "กำลังเรียน", COMPLETED: "เสร็จแล้ว", FAILED: "ไม่สำเร็จ" };
    for (const run of runs.slice(0, 12)) {
      const row = element("article", "learning-run");
      row.dataset.state = String(run.status || "").toLowerCase();
      const heading = element("div", "learning-run-heading");
      heading.append(element("strong", "", topicNames.get(run.topicId) || run.objective));
      heading.append(element("span", "", runStatusLabels[run.status] || run.status));
      const counts = element("div", "learning-run-counts");
      counts.append(
        element("span", "", `${run.sourceCount || 0} แหล่ง`),
        element("span", "", `${run.candidateCount || 0} ข้อเท็จจริง`),
        element("span", "", `${run.publishedCount || 0} ผ่านอัตโนมัติ`)
      );
      const detail = element("small", "", `${run.trigger === "SCHEDULED" ? "ตามตาราง" : "สั่งเอง"} · ${dateTime(run.completedAt || run.startedAt)} · ${run.stopReason || "—"}`);
      row.append(heading, counts, detail);
      runList.append(row);
    }
  }

  async function runLearningTopic(topic, button) {
    state.learningBusy.add(topic.id);
    button.disabled = true;
    button.textContent = "กำลังค้นและอ่าน…";
    try {
      const run = await api(`/v1/knowledge/acquisition/topics/${topic.id}/runs`, { method: "POST" });
      toast(`เรียนจบแล้ว: ${run.sourceCount} แหล่ง · ผ่านอัตโนมัติ ${run.publishedCount} ข้อ`);
      state.learningBusy.delete(topic.id);
      await loadDashboard();
    } catch (error) { toast(error.message, true); }
    finally {
      state.learningBusy.delete(topic.id);
      button.disabled = false;
      button.textContent = "เรียนตอนนี้";
    }
  }

  async function setLearningTopicStatus(topic, button) {
    button.disabled = true;
    const status = topic.status === "ACTIVE" ? "PAUSED" : "ACTIVE";
    try {
      await api(`/v1/knowledge/acquisition/topics/${topic.id}`, {
        method: "PATCH", body: JSON.stringify({ owner_id: state.ownerId, status })
      });
      toast(status === "ACTIVE" ? "เปิดตารางเรียนแล้วครับ" : "พักตารางเรียนแล้วครับ");
      await loadDashboard();
    } catch (error) { toast(error.message, true); button.disabled = false; }
  }

  async function reviewLearningClaim(claim, status, button) {
    button.disabled = true;
    try {
      await api(`/v1/knowledge/acquisition/claims/${claim.id}`, {
        method: "PATCH", body: JSON.stringify({ owner_id: state.ownerId, status })
      });
      toast(status === "PUBLISHED" ? "ยืนยันให้ใช้ข้อเท็จจริงนี้ตอบแล้วครับ" : "กันข้อเท็จจริงนี้ออกแล้วครับ");
      await loadDashboard();
    } catch (error) { toast(error.message, true); button.disabled = false; }
  }

  function renderMemorySystem(memories = [], knowledge = {}, sources = [], claims = []) {
    const published = claims.filter((claim) => claim.status === "PUBLISHED");
    $("#memory-lane-count").textContent = `${memories.length.toLocaleString("th-TH")} รายการ`;
    $("#knowledge-lane-count").textContent = `${Number(knowledge.chunks || 0).toLocaleString("th-TH")} ส่วน`;
    $("#acquired-lane-count").textContent = `${published.length.toLocaleString("th-TH")} ข้อเท็จจริง`;
    $("#memory-record-count").textContent = `${memories.length.toLocaleString("th-TH")} รายการ`;
    $("#knowledge-ready-count").textContent = `${(Number(knowledge.chunks || 0) + published.length).toLocaleString("th-TH")} พร้อมใช้`;

    const categoryLabels = { FACT: "ข้อมูล", PREFERENCE: "ความชอบ", GOAL: "เป้าหมาย", CONSTRAINT: "ข้อจำกัด", RELATIONSHIP: "ความสัมพันธ์", ROUTINE: "กิจวัตร" };
    const records = $("#memory-record-list");
    records.replaceChildren();
    $("#memory-record-empty").classList.toggle("hidden", memories.length > 0);
    for (const memory of memories.slice(0, 20)) {
      const row = element("article", "memory-record");
      const copy = element("div");
      const meta = element("div", "memory-record-meta");
      meta.append(
        element("span", "", categoryLabels[memory.category] || memory.category),
        element("small", "", `${percentage(memory.confidence)} · ${dateTime(memory.createdAt)}`)
      );
      copy.append(meta, element("p", "", memory.content));
      if (memory.reason) copy.append(element("small", "memory-reason", `เหตุผลที่จำ: ${memory.reason}`));
      const remove = element("button", "text-button", "ลบความจำ");
      remove.type = "button";
      remove.addEventListener("click", () => deleteMemoryRecord(memory, remove));
      row.append(copy, remove);
      records.append(row);
    }

    const sourceList = $("#knowledge-source-list");
    sourceList.replaceChildren();
    for (const source of sources.slice(0, 12)) {
      const row = element("article", "knowledge-source-row");
      const copy = element("div");
      copy.append(element("strong", "", source.name || source.path));
      copy.append(element("small", "", `${source.root} · ${source.chunkCount || 0} ส่วน · ${source.status}`));
      row.append(copy, element("time", "", dateTime(source.indexedAt)));
      sourceList.append(row);
    }
    if (!sources.length) sourceList.append(element("p", "library-empty", "ยังไม่มีไฟล์ที่ทำดัชนี"));

    const publishedList = $("#published-claim-list");
    publishedList.replaceChildren();
    for (const claim of published.slice(0, 12)) {
      const row = element("article", "published-claim");
      row.append(element("span", "", claim.topicName), element("p", "", claim.text));
      const citations = element("div", "claim-citations");
      for (const url of (claim.evidenceUrls || []).slice(0, 2)) {
        const safe = safeExternalUrl(url);
        if (!safe) continue;
        const link = element("a", "", `อ้างอิง · ${new URL(safe).hostname.replace(/^www\./, "")}`);
        link.href = safe; link.target = "_blank"; link.rel = "noopener noreferrer";
        citations.append(link);
      }
      row.append(citations);
      publishedList.append(row);
    }
    if (!published.length) publishedList.append(element("p", "library-empty", "ยังไม่มีข้อเท็จจริงที่ผ่านการตรวจ"));
  }

  async function deleteMemoryRecord(memory, button) {
    const id = memory.id?.value || memory.id;
    if (!id || !await requestConfirmation("ลบความจำนี้?", "ลบความจำรายการนี้ออกจากมินิคุงใช่ไหมครับ?", "ลบความจำ")) return;
    button.disabled = true;
    try {
      await api(`/v1/memory/${id}`, { method: "DELETE" });
      toast("ลบความจำรายการนี้แล้วครับ");
      await loadDashboard();
    } catch (error) { toast(error.message, true); button.disabled = false; }
  }

  const permissionLabels = {
    granted: "อนุญาตแล้ว", denied: "ถูกปิด", prompt: "ยังไม่อนุญาต",
    unknown: "ตรวจสอบกับเบราว์เซอร์", unsupported: "ไม่รองรับ", insecure: "ต้องใช้ HTTPS"
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
        : status === "insecure" ? "ดูวิธีเปิด" : status === "unknown" ? "ทดสอบ" : "เปิดใช้";
  }

  async function browserPermission(name) {
    if (!navigator.permissions?.query) return "unknown";
    try { return (await navigator.permissions.query({ name })).state; }
    catch (_) { return "unknown"; }
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
      ? `เบราว์เซอร์จำสิทธิ์แยกตามที่อยู่เว็บนี้ (${window.location.origin}) เปิดผ่านที่อยู่เดิมทุกครั้งครับ`
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
        accuracy: Math.round(position.coords.accuracy),
        captured_at: Number(position.timestamp) || Date.now()
      };
      setPermissionUi("location", "granted", `ใช้กับแชตนี้ · แม่นยำประมาณ ${state.currentLocation.accuracy} ม.`);
      toast("เปิดตำแหน่งให้แชตนี้แล้วครับ");
    }, (error) => {
      setPermissionUi("location", error.code === 1 ? "denied" : "prompt");
      if (error.code === 1) showPermissionHelp("location");
      else toast("อ่านตำแหน่งไม่สำเร็จ ลองอีกครั้งได้ครับ", true);
    }, { enableHighAccuracy: true, timeout: 12000, maximumAge: 0 });
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
    const alreadyGranted = Notification.permission === "granted";
    const result = await Notification.requestPermission();
    setPermissionUi("notifications", result);
    if (result === "denied") showPermissionHelp("notifications");
    else if (result === "granted" && alreadyGranted) {
      showBrowserNotification({ title: "Mini-kun", message: "การแจ้งเตือนพร้อมใช้งานแล้วครับ" }, "permission-test", true);
    } else {
      toast(result === "granted" ? "อนุญาตการแจ้งเตือนแล้วครับ" : "ยังไม่ได้เปิดการแจ้งเตือนครับ", result !== "granted");
    }
  }

  function updateSystemPolling() {
    clearInterval(state.dashboard.timer);
    state.dashboard.timer = null;
    if (document.hidden || state.cockpitPage !== "system" || $("#cockpit-view").classList.contains("hidden")) return;
    state.dashboard.timer = setInterval(() => {
      return loadDashboard("system", { force: true, healthOnly: true }).catch(() => {});
    }, 5_000);
  }

  async function loadDashboard(page = state.cockpitPage, options = {}) {
    const force = options.force ?? true;
    const loadedAt = state.dashboard.loadedAt.get(page) || 0;
    if (!force && Date.now() - loadedAt < 15_000) return;
    const existing = state.dashboard.loading.get(page);
    if (existing) return existing;

    const requests = {
      today: [
        ["status", "/v1/personal/status"],
        ["actions", "/v1/personal/next-actions?limit=5"],
        ["inbox", "/v1/personal/inbox?limit=20"],
        ["decisions", "/v1/personal/automations/runs?limit=20"]
      ],
      agent: [
        ["timeline", `/v1/personal/timeline?limit=${state.timelineLimit}`],
        ["experiments", "/v1/personal/experiments?limit=20"],
        ["agentRuns", "/v1/agent/runs?limit=12"],
        ["threads", "/v1/personal/conversation-threads?status=OPEN&limit=20"]
      ],
      memory: [
        ["memories", "/v1/memory?limit=30"],
        ["knowledge", "/v1/knowledge/status"],
        ["knowledgeSources", "/v1/knowledge/sources?limit=30"],
        ["learningTopics", "/v1/knowledge/acquisition/topics?limit=50"],
        ["learningRuns", "/v1/knowledge/acquisition/runs?limit=50"],
        ["learningCandidates", "/v1/knowledge/acquisition/claims?status=CANDIDATE&limit=100"],
        ["learningPublished", "/v1/knowledge/acquisition/claims?status=PUBLISHED&limit=100"]
      ],
      system: [
        ["systemHealth", "/v1/system/health"],
        ["evalBaseline", "/v1/evals/turn-plans/baseline"],
        ["evalQuality", "/v1/evals/turn-plans/quality?limit=100"]
      ]
    };

    const operation = (async () => {
      if (!options.healthOnly) setSync(true, "กำลังทบทวน");
      const entries = options.healthOnly ? requests.system.slice(0, 1) : requests[page] || requests.today;
      const calls = await Promise.allSettled(entries.map(([, path]) => api(path)));
      const results = new Map(entries.map(([key], index) => [key, calls[index]]));
      const value = (key, fallback = []) => {
        const result = results.get(key);
        return result?.status === "fulfilled" ? result.value : fallback;
      };
      const list = (key) => {
        const result = value(key);
        return Array.isArray(result) ? result : [];
      };
      const learningClaims = [...list("learningCandidates"), ...list("learningPublished")];

      if (page === "today") {
        const decisionCount = renderDecisions(value("decisions"));
        renderStatus(value("status", null), decisionCount);
        renderActions(value("actions"));
        renderInbox(value("inbox"));
      } else if (page === "agent") {
        renderExperiment(value("experiments"));
        renderTimeline(value("timeline"));
        renderConversationThreads(value("threads"));
        const runItems = value("agentRuns");
        renderAgentRuns(runItems);
        await loadToolTimeline(runItems);
      } else if (page === "memory") {
        renderContextMemory(value("memories"), value("knowledge", {}));
        renderLearning(value("learningTopics"), value("learningRuns"), learningClaims);
        renderMemorySystem(value("memories"), value("knowledge", {}), value("knowledgeSources"), learningClaims);
      } else if (page === "system") {
        renderSystemHealth(value("systemHealth", null));
        if (!options.healthOnly) renderEvalLab(value("evalBaseline", null), value("evalQuality", null));
      }

      if (!options.healthOnly) state.dashboard.loadedAt.set(page, Date.now());
      const failures = calls.filter((call) => call.status === "rejected");
      if (state.cockpitPage === page) {
        setSync(failures.length === 0, failures.length ? `${failures.length} ส่วนยังไม่พร้อม` : "พร้อมดูแล");
        if (failures.length === calls.length && !options.healthOnly) {
          setSync(false, "เชื่อมต่อไม่ได้");
          toast("เชื่อมต่อกับมินิคุงไม่ได้ครับ เปิดการตั้งค่าเพื่อตรวจอุปกรณ์และการเชื่อมต่อได้เลย", true, {
            label: "เปิดการตั้งค่า",
            onClick: () => {
              if (!settingsDialog.open) settingsDialog.showModal();
              loadPairedDevices();
              loadCompanionMode();
            }
          });
        }
      }
    })();

    state.dashboard.loading.set(page, operation);
    try {
      return await operation;
    } finally {
      if (state.dashboard.loading.get(page) === operation) state.dashboard.loading.delete(page);
    }
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
  showActiveModel(state.model);
  $("#voice-output").setAttribute("aria-pressed", String(state.voiceOutput));
  if (isAppleMobile()) {
    $("#composer-hint").textContent = "Enter ขึ้นบรรทัดใหม่ · แตะ ↑ เพื่อส่ง";
  }

  try {
    const pending = JSON.parse(localStorage.getItem("minikun.sync-pending") || "[]");
    if (Array.isArray(pending)) state.sync.pending = new Set(pending.filter((id) => typeof id === "string"));
  } catch (_) { /* keep local history if the retry queue is corrupt */ }
  state.conversations = safeConversationList();
  state.sync.pending = new Set([...state.sync.pending].filter((id) =>
    state.conversations.some((conversation) => conversation.id === id)));
  const requestedConversationId = new URLSearchParams(window.location.search).get("conversation_id");
  const safeRequestedConversationId = /^[a-zA-Z0-9._:-]{1,200}$/.test(String(requestedConversationId || "").trim())
    ? requestedConversationId.trim() : "";
  const requestedConversation = state.conversations.find((conversation) => conversation.id === safeRequestedConversationId);
  if (requestedConversation) {
    state.currentConversationId = requestedConversation.id;
    state.chatMessages = requestedConversation.messages;
  } else if (safeRequestedConversationId) {
    state.currentConversationId = safeRequestedConversationId;
    state.chatMessages = [];
  } else if (state.conversations.length) {
    state.currentConversationId = state.conversations[0].id;
    state.chatMessages = Array.isArray(state.conversations[0].messages) ? state.conversations[0].messages : [];
  } else {
    state.currentConversationId = chatId();
  }
  renderConversationList();
  renderChat();
  syncChatState();
  restoreDraft();
  resumeBackgroundChats();
  loadRuntimeModels().catch(() => {});

  $("#visual-ask").addEventListener("click", () => state.activeVisual && attachVisualReference(state.activeVisual));
  $("#visual-similar").addEventListener("click", () => state.activeVisual && findSimilarVisual(state.activeVisual));
  $("#visual-create").addEventListener("click", () => state.activeVisual && attachVisualReference(state.activeVisual, true));
  $("#visual-save").addEventListener("click", () => {
    if (state.activeVisual) saveVisualReference(state.activeVisual).catch(error => toast(error.message, true));
  });
  $("#open-inspiration-boards").addEventListener("click", async () => {
    inspirationBoardsDialog.showModal();
    await loadInspirationBoards();
  });
  $("#studio-open-boards").addEventListener("click", async () => {
    inspirationBoardsDialog.showModal();
    await loadInspirationBoards();
  });
  $("#studio-form").addEventListener("submit", generateStudioImage);
  $("#studio-transform-prompt").addEventListener("click", transformStudioPrompt);
  $("#studio-brief").addEventListener("input", () => {
    $("#studio-transform-status").textContent = "มินิคุงจะรักษาตัวละคร ฉาก สี และการกระทำที่พี่เล่าไว้";
  });
  document.querySelectorAll("[data-studio-brief-add]").forEach((button) => {
    button.addEventListener("click", () => {
      const brief = $("#studio-brief");
      brief.value = `${brief.value.trim()}${button.dataset.studioBriefAdd}`.trim();
      brief.focus();
    });
  });
  $("#studio-prompt-preview").addEventListener("input", () => updateStudioOutputState());
  ["#studio-subject", "#studio-scene", "#studio-detail", "#studio-constraints"].forEach((selector) => {
    $(selector).addEventListener("input", updateStudioPrompt);
  });
  document.querySelectorAll("[data-studio-option]").forEach((button) => {
    button.addEventListener("click", () => selectStudioOption(button));
  });
  document.querySelectorAll("[data-studio-size]").forEach((button) => {
    button.addEventListener("click", () => selectStudioSize(button));
  });
  ["#studio-steps", "#studio-guidance"].forEach((selector) => {
    $(selector).addEventListener("input", updateStudioCanvasMeta);
  });
  $("#studio-random-seed").addEventListener("click", () => {
    const values = new Uint32Array(1);
    window.crypto.getRandomValues(values);
    $("#studio-seed").value = String(values[0] & 0x7fffffff);
    toast(`ใช้ seed ${$("#studio-seed").value} สำหรับภาพนี้ครับ`);
  });
  document.querySelectorAll("[data-studio-step] button").forEach((button) => {
    button.addEventListener("click", () => {
      const step = button.closest("[data-studio-step]").dataset.studioStep;
      setStudioActiveStep(step);
      const field = document.querySelector(`[data-studio-field="${step}"]`);
      const control = field?.matches("fieldset") ? field.querySelector("button") : field?.querySelector("textarea,input,button") || field;
      control?.focus();
      field?.scrollIntoView({ behavior: "smooth", block: "center" });
    });
  });
  document.querySelectorAll("[data-studio-field]").forEach((field) => {
    field.addEventListener("focusin", () => {
      if (document.querySelector(`[data-studio-step="${field.dataset.studioField}"]`)) {
        setStudioActiveStep(field.dataset.studioField);
      }
    });
  });
  $("#studio-load-example").addEventListener("click", loadStudioExample);
  $("#studio-use-manual").addEventListener("click", () => {
    const prompt = assembledManualPrompt();
    if (!prompt) {
      $("#studio-subject").focus();
      toast("ใส่ character tags ใน Manual Pony ก่อนครับ", true);
      return;
    }
    $("#studio-prompt-preview").value = prompt;
    updateStudioOutputState("ใช้ tags จาก Manual Pony แล้ว แก้ต่อได้ก่อนสร้างภาพครับ");
    $("#studio-prompt-preview").focus();
  });
  $("#studio-copy-prompt").addEventListener("click", async () => {
    const prompt = assembledStudioPrompt();
    if (!prompt) {
      $("#studio-brief").focus();
      toast("แปลงหรือวาง Pony prompt ก่อนคัดลอกครับ", true);
      return;
    }
    try {
      await copyText(prompt);
      $("#studio-copy-prompt").textContent = "คัดลอกแล้ว";
      setTimeout(() => { $("#studio-copy-prompt").textContent = "คัดลอก"; }, 1400);
    } catch (error) { toast(error.message, true); }
  });
  $("#studio-save-result").addEventListener("click", () => {
    if (state.studio.result) saveVisualReference(state.studio.result).catch(error => toast(error.message, true));
  });
  updateStudioPrompt();
  updateStudioOutputState();
  updateStudioCanvasMeta();
  $("#create-inspiration-board").addEventListener("submit", async (event) => {
    event.preventDefault();
    const title = $("#new-board-title").value.trim();
    if (!title) return;
    try {
      await api("/v1/personal/inspiration-boards", {
        method: "POST", body: JSON.stringify({ owner_id: state.ownerId, title })
      });
      $("#new-board-title").value = "";
      await loadInspirationBoards();
    } catch (error) { toast(error.message, true); }
  });

  document.querySelectorAll("[data-close-dialog]").forEach((button) => {
    button.addEventListener("click", () => button.closest("dialog")?.close("cancel"));
  });
  document.querySelectorAll("dialog").forEach((dialog) => {
    dialog.addEventListener("click", (event) => {
      if (event.target === dialog) dialog.close("cancel");
    });
  });
  confirmDialog.addEventListener("close", () => {
    if (confirmationResolve) finishConfirmation(false);
  });
  $("#confirm-form").addEventListener("submit", (event) => {
    event.preventDefault();
    finishConfirmation(true);
  });
  $("#confirm-action").addEventListener("click", () => finishConfirmation(true));
  feedbackDialog.addEventListener("close", () => {
    if (feedbackResolve) finishFeedback("");
  });
  $("#feedback-form").addEventListener("submit", (event) => {
    event.preventDefault();
    finishFeedback($("#feedback-reason").value.trim());
  });
  editMessageDialog.addEventListener("close", () => {
    if (editMessageResolve) finishEditedMessage(null);
  });
  $("#edit-message-form").addEventListener("submit", (event) => {
    event.preventDefault();
    finishEditedMessage($("#edit-message-input").value.trim());
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
      showView(button.dataset.viewTarget, button.dataset.section || "", { updateHistory: true, focus: true });
    });
  });
  $("#open-voice-room").addEventListener("click", (event) => {
    event.preventDefault();
    const url = new URL("/cockpit/voice-room.html", window.location.origin);
    url.searchParams.set("conversation_id", state.currentConversationId);
    window.location.assign(`${url.pathname}${url.search}`);
  });
  window.addEventListener("popstate", () => {
    const params = new URLSearchParams(window.location.search);
    const view = params.get("view");
    const section = params.get("section") || "";
    showView(new Set(["chat", "studio", "cockpit"]).has(view) ? view : "chat", section);
  });
  document.querySelectorAll("[data-prompt]").forEach((button) => {
    button.addEventListener("click", () => {
      const composer = $("#chat-composer");
      composer.value = button.dataset.prompt;
      autoGrowComposer();
      composer.focus();
    });
  });
  document.querySelectorAll(".starter-more-toggle").forEach((button) => {
    button.addEventListener("click", () => {
      const more = button.closest(".starter-more");
      const collapsed = more.classList.toggle("is-collapsed");
      button.setAttribute("aria-expanded", String(!collapsed));
      button.textContent = collapsed ? "ดูอีก 2 แนวทาง" : "ซ่อนแนวทางเพิ่มเติม";
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
  $("#stop-message").addEventListener("click", () => {
    const task = state.pendingChats.get(state.currentConversationId);
    if (!task) return;
    task.controller.abort();
    if (task.jobId) api(`/v1/chat/background/${encodeURIComponent(task.jobId)}`, { method: "DELETE" }).catch(() => {});
  });
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
      for (const timer of state.sync.timers.values()) window.clearTimeout(timer);
      state.sync.timers.clear();
      await Promise.all([...state.sync.inFlight.values()]);
      await Promise.all(state.conversations.map((conversation) =>
        api(`/v1/conversations/${encodeURIComponent(conversation.id)}`, { method: "DELETE" })));
      if (state.sync.paired) await syncFetch("/v1/sync/conversations", { method: "DELETE" });
      localStorage.removeItem("minikun.conversations");
      for (const conversationId of state.sync.pending) forgetConversationSync(conversationId);
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
    if (state.studio.runtimeTimer) clearInterval(state.studio.runtimeTimer);
    clearInterval(state.dashboard.timer);
    stopSpeech();
  });
  document.addEventListener("visibilitychange", () => {
    updateStudioRuntimePolling($("#studio-view")?.classList.contains("hidden") ? "" : "studio");
    updateSystemPolling();
    if (state.dashboard.timer) loadDashboard("system", { force: true, healthOnly: true }).catch(() => {});
  });

  function openSettings() {
    if (!settingsDialog.open) settingsDialog.showModal();
    renderPermissions().catch((error) => toast(error.message, true));
    loadPairedDevices();
    loadCompanionMode();
    loadRuntimeModels().catch((error) => toast(error.message, true));
  }
  $("#open-settings").addEventListener("click", openSettings);
  $("#mobile-menu-settings").addEventListener("click", () => {
    mobileMenuDialog.close();
    openSettings();
  });
  $("#create-pairing").addEventListener("click", createDevicePairing);
  $("#copy-pair-link").addEventListener("click", async () => {
    try {
      await copyText(state.sync.pairingUrl);
      toast("คัดลอกลิงก์จับคู่แล้วครับ");
    } catch (error) { toast(error.message, true); }
  });
  $("#refresh").addEventListener("click", () => loadDashboard(state.cockpitPage, { force: true }));
  $("#health-retry").addEventListener("click", () => loadDashboard(state.cockpitPage, { force: true }));
  document.querySelectorAll("[data-quick-action]").forEach((button) => {
    button.addEventListener("click", () => activateQuickAction(button.dataset.quickAction));
  });
  document.querySelectorAll("[data-cockpit-target]").forEach((button) => {
    button.addEventListener("click", () => {
      switchCockpitPage(button.dataset.cockpitTarget);
      updateViewUrl("cockpit", button.dataset.cockpitTarget);
      button.blur();
    });
  });
  $("#request-location").addEventListener("click", requestCurrentLocation);
  $("#request-microphone").addEventListener("click", requestMicrophonePermission);
  $("#request-notifications").addEventListener("click", requestNotificationPermission);
  $("#new-experiment").addEventListener("click", () => experimentDialog.showModal());
  $("#new-learning-topic").addEventListener("click", () => learningTopicDialog.showModal());
  document.querySelectorAll("[data-open-experiment]").forEach((button) => {
    button.addEventListener("click", () => experimentDialog.showModal());
  });
  $("#capture-form").addEventListener("submit", capture);
  $("#experiment-form").addEventListener("submit", createExperiment);
  $("#learning-topic-form").addEventListener("submit", async (event) => {
    if (event.submitter?.value !== "default") return;
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const trustedDomains = String(data.get("trustedDomains") || "").split(",")
      .map((value) => value.trim()).filter(Boolean);
    const sourcePolicy = String(data.get("sourcePolicy"));
    if (sourcePolicy === "OFFICIAL_ONLY" && !trustedDomains.length) {
      toast("หัวข้อที่ใช้เฉพาะแหล่งทางการต้องระบุ trusted domain อย่างน้อยหนึ่งแห่งครับ", true);
      return;
    }
    try {
      await api("/v1/knowledge/acquisition/topics", {
        method: "POST", body: JSON.stringify({
          owner_id: state.ownerId,
          name: data.get("name"), objective: data.get("objective"), origin: "SUBSCRIBED",
          priority: Number(data.get("priority")), refresh_policy: data.get("refreshPolicy"),
          source_policy: sourcePolicy, trusted_domains: trustedDomains, status: "ACTIVE"
        })
      });
      form.reset();
      learningTopicDialog.close();
      toast("เพิ่มหัวข้อและเปิดตารางเรียนแล้วครับ");
      await loadDashboard();
    } catch (error) { toast(error.message, true); }
  });
  $("#experiment-action-form").addEventListener("submit", submitExperimentAction);
  $("#settings-form").addEventListener("submit", async (event) => {
    if (event.submitter?.value === "cancel") return;
    event.preventDefault();
    state.ownerId = $("#owner-id").value.trim() || "default";
    state.token = $("#personal-token").value;
    sessionStorage.setItem("minikun.owner", state.ownerId);
    sessionStorage.setItem("minikun.token", state.token);
    try {
      const selectedModel = $("#chat-model").value;
      if (!selectedModel) {
        await loadRuntimeModels();
        toast("เลือกโมเดลแล้วกดบันทึกอีกครั้งครับ");
        return;
      }
      const catalog = await api("/v1/models/runtime", {
        method: "PUT", body: JSON.stringify({ model: selectedModel })
      });
      showActiveModel(catalog.active);
      await saveCompanionMode();
      settingsDialog.close();
      toast("บันทึกการตั้งค่าแล้วครับ");
    } catch (error) { toast(error.message, true); }
  });
  $("#show-all-timeline").addEventListener("click", () => {
    state.timelineLimit = Math.min(50, state.timelineLimit + 10);
    loadDashboard();
  });

  const requestedParams = new URLSearchParams(window.location.search);
  const requestedView = requestedParams.get("view");
  showView(new Set(["chat", "studio", "cockpit"]).has(requestedView) ? requestedView : "chat", requestedParams.get("section") || "");
  initializeSync();
  window.addEventListener("online", retryPendingSync);
})();
