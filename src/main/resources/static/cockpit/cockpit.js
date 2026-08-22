(() => {
  "use strict";

  const state = {
    ownerId: sessionStorage.getItem("minikun.owner") || "default",
    token: sessionStorage.getItem("minikun.token") || "",
    model: localStorage.getItem("minikun.model") || "mini-kun",
    conversations: [],
    currentConversationId: "",
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
    timelineLimit: 6,
    experimentAction: null
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
      return Array.isArray(parsed) ? parsed.slice(0, 40) : [];
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
      role: message.role,
      content: message.content,
      createdAt: message.createdAt,
      usage: message.usage,
      timing: message.timing ? {
        firstTokenMs: message.timing.firstTokenMs,
        totalMs: message.timing.totalMs
      } : undefined,
      attachments: (message.attachments || [])
        .filter((attachment) => attachment.url && !attachment.url.startsWith("data:"))
        .slice(0, 6)
    }));
  }

  function conversationTitle(text) {
    const clean = String(text || "แชตใหม่").replace(/\s+/g, " ").trim();
    return clean.length > 42 ? `${clean.slice(0, 42)}…` : clean || "แชตใหม่";
  }

  function saveConversation(seed = "", conversationId = state.currentConversationId, messages = state.chatMessages) {
    let current = state.conversations.find((item) => item.id === conversationId);
    if (!current) {
      current = { id: conversationId, title: conversationTitle(seed), updatedAt: Date.now(), messages: [] };
      state.conversations.unshift(current);
    }
    if (current.title === "แชตใหม่" && seed) current.title = conversationTitle(seed);
    current.updatedAt = Date.now();
    current.messages = compactMessages(messages);
    state.conversations.sort((a, b) => b.updatedAt - a.updatedAt);
    persistConversations();
    renderConversationList();
  }

  function renderConversationList() {
    for (const list of document.querySelectorAll("[data-conversation-list]")) {
      list.replaceChildren();
      for (const conversation of state.conversations) {
        const item = element("li");
        const button = element("button", conversation.id === state.currentConversationId ? "active" : "");
        const pending = state.pendingChats.has(conversation.id);
        button.classList.toggle("pending", pending);
        button.type = "button";
        button.title = pending ? `${conversation.title} — มินิคุงกำลังตอบ` : conversation.title;
        button.append(element("span", "", conversation.title));
        button.addEventListener("click", () => {
          mobileMenuDialog?.close();
          switchConversation(conversation.id);
        });
        item.append(button);
        list.append(item);
      }
    }
    document.querySelectorAll("[data-conversation-empty]").forEach((node) => {
      node.classList.toggle("hidden", state.conversations.length > 0);
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
  }

  function showView(view, section = "") {
    document.querySelectorAll("[data-view]").forEach((node) => node.classList.toggle("hidden", node.dataset.view !== view));
    document.querySelectorAll("[data-view-target]").forEach((node) => {
      const matchesView = node.dataset.viewTarget === view;
      const matchesSection = section ? node.dataset.section === section : !node.dataset.section;
      node.classList.toggle("active", matchesView && matchesSection);
    });
    if (view === "cockpit") {
      loadDashboard();
      if (section) requestAnimationFrame(() => document.getElementById(section)?.scrollIntoView({ behavior: "smooth", block: "start" }));
    }
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

  function renderResponseMeta(message) {
    if (message.role !== "assistant") return null;
    const values = [];
    const contextTokens = Number(message.usage?.promptTokens);
    if (contextTokens > 0) values.push(`Context ${contextTokens.toLocaleString("th-TH")} tokens`);
    const thinkingMs = Number(message.timing?.firstTokenMs || message.timing?.totalMs);
    if (thinkingMs > 0) values.push(`คิด ${formatDuration(thinkingMs)}`);
    const totalMs = Number(message.timing?.totalMs);
    if (totalMs > thinkingMs) values.push(`รวม ${formatDuration(totalMs)}`);
    if (!values.length) return null;
    const meta = element("div", "response-meta");
    values.forEach((value) => meta.append(element("span", "", value)));
    return meta;
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
      const typing = element("span", "typing");
      typing.innerHTML = "<i></i><i></i><i></i>";
      content.append(typing);
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

  async function addFiles(files) {
    for (const file of Array.from(files).slice(0, 6)) {
      if (file.size > 10 * 1024 * 1024) {
        toast(`${file.name} ใหญ่เกิน 10 MB ครับ`, true);
        continue;
      }
      try {
        if (file.type.startsWith("image/")) {
          state.attachments.push({ kind: "image", name: file.name, type: file.type, data: await fileAsDataUrl(file) });
        } else {
          if (file.size > 500 * 1024) {
            toast(`${file.name} ใหญ่เกิน 500 KB สำหรับไฟล์ข้อความครับ`, true);
            continue;
          }
          state.attachments.push({ kind: "text", name: file.name, type: file.type, text: await fileAsText(file) });
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
    if (!images.length) return combined;
    return [
      { type: "text", text: combined || "ช่วยดูภาพที่แนบมานี้ให้หน่อยครับ" },
      ...images.map((image) => ({ type: "image_url", image_url: { url: image.data, detail: "auto" } }))
    ];
  }

  async function consumeChatStream(response, assistant) {
    if (!response.body) throw new Error("Browser นี้ยังไม่รองรับ streaming response");
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    const update = () => {
      const node = document.querySelector(`[data-message-id="${assistant.id}"] .message-content`);
      if (node) node.innerHTML = assistant.content ? markdown(assistant.content) : '<span class="typing"><i></i><i></i><i></i></span>';
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
          if (delta?.content) {
            if (!assistant.timing.firstTokenMs) assistant.timing.firstTokenMs = Date.now() - assistant.timing.startedAt;
            assistant.content += delta.content;
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
      attachments: attachments.filter((item) => item.kind === "image").map((item) => ({ title: item.name, url: item.data })),
      createdAt: Date.now()
    };
    const assistant = {
      id: uniqueId("message-"), role: "assistant", content: "", attachments: [], createdAt: Date.now(),
      timing: { startedAt: Date.now(), firstTokenMs: 0, totalMs: 0 }
    };
    messages.push(userMessage);
    composer.value = "";
    autoGrowComposer();
    renderAttachmentTray();
    $("#chat-welcome").classList.add("hidden");
    $("#messages").append(renderMessage(userMessage), renderMessage(assistant, true));
    scrollToLatest();
    saveConversation(text || files[0], conversationId, messages);
    const task = { controller: new AbortController(), assistant, status: "กำลังคิดและเลือกเครื่องมือ…" };
    state.pendingChats.set(conversationId, task);
    renderConversationList();
    syncChatState();
    try {
      const response = await fetch("/v1/chat/completions", {
        method: "POST",
        headers: headers(true),
        signal: task.controller.signal,
        body: JSON.stringify({
          model: state.model,
          messages: [{ role: "user", content: requestContent(text, attachments) }],
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
      await consumeChatStream(response, assistant);
      assistant.timing.totalMs = Date.now() - assistant.timing.startedAt;
      if (!assistant.content && !assistant.attachments.length) assistant.content = "ได้รับข้อความแล้วครับ แต่ยังไม่มีคำตอบกลับมา";
      messages.push(assistant);
      saveConversation(text || files[0], conversationId, messages);
      if (state.currentConversationId === conversationId) {
        renderChat();
        if (state.voiceOutput && assistant.content) speak(assistant.content);
      }
    } catch (error) {
      if (error.name === "AbortError") {
        assistant.timing.totalMs = Date.now() - assistant.timing.startedAt;
        if (assistant.content) {
          assistant.content += "\n\n_หยุดคำตอบแล้ว_";
          messages.push(assistant);
          saveConversation(text || files[0], conversationId, messages);
        }
        if (state.currentConversationId === conversationId) renderChat();
      } else {
        assistant.timing.totalMs = Date.now() - assistant.timing.startedAt;
        assistant.content = `ขออภัยครับ ตอนนี้เชื่อมต่อไม่สำเร็จ\n\n${error.message}`;
        messages.push(assistant);
        saveConversation(text || files[0], conversationId, messages);
        if (state.currentConversationId === conversationId) renderChat();
        toast(error.message, true);
      }
    } finally {
      if (state.pendingChats.get(conversationId) === task) state.pendingChats.delete(conversationId);
      renderConversationList();
      syncChatState();
      if (state.currentConversationId === conversationId && !window.matchMedia("(pointer: coarse)").matches) composer.focus();
    }
  }

  async function speak(text) {
    try {
      const response = await fetch("/v1/audio/speech", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ model: "minikun-voice", input: text.slice(0, 12000), voice: "minikun", response_format: "wav", speed: 1 })
      });
      if (!response.ok) throw new Error("ระบบเสียงยังไม่พร้อม");
      const url = URL.createObjectURL(await response.blob());
      const audio = new Audio(url);
      audio.addEventListener("ended", () => URL.revokeObjectURL(url), { once: true });
      await audio.play();
    } catch (error) { toast(error.message, true); }
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
      toast("เปิดไมโครโฟนไม่สำเร็จ กรุณาอนุญาตการใช้งานก่อนครับ", true);
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

  async function loadDashboard() {
    setSync(true, "กำลังทบทวน");
    const calls = await Promise.allSettled([
      api("/v1/personal/status"),
      api("/v1/personal/next-actions?limit=5"),
      api("/v1/personal/experiments?limit=20"),
      api("/v1/personal/inbox?limit=20"),
      api(`/v1/personal/timeline?limit=${state.timelineLimit}`),
      api("/v1/personal/automations/runs?limit=20")
    ]);
    const [status, actions, experiments, inbox, timeline, decisions] = calls;
    const decisionCount = renderDecisions(decisions.status === "fulfilled" ? decisions.value : []);
    renderStatus(status.status === "fulfilled" ? status.value : {}, decisionCount);
    renderActions(actions.status === "fulfilled" ? actions.value : []);
    renderExperiment(experiments.status === "fulfilled" ? experiments.value : []);
    renderInbox(inbox.status === "fulfilled" ? inbox.value : []);
    renderTimeline(timeline.status === "fulfilled" ? timeline.value : []);
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

  state.conversations = safeConversationList();
  if (state.conversations.length) {
    state.currentConversationId = state.conversations[0].id;
    state.chatMessages = Array.isArray(state.conversations[0].messages) ? state.conversations[0].messages : [];
  } else {
    state.currentConversationId = chatId();
  }
  renderConversationList();
  renderChat();

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
  $("#chat-composer").addEventListener("input", autoGrowComposer);
  $("#chat-composer").addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
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
    toast(state.voiceOutput ? "จะอ่านคำตอบใหม่ออกเสียงครับ" : "ปิดการอ่านคำตอบแล้วครับ");
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
  $("#confirm-clear-history").addEventListener("click", () => {
    localStorage.removeItem("minikun.conversations");
    state.conversations = [];
    startNewChat();
    toast("ล้างรายการบทสนทนาบนอุปกรณ์นี้แล้วครับ");
  });
  window.addEventListener("beforeunload", () => {
    state.mediaStream?.getTracks().forEach((track) => track.stop());
    state.audioContext?.close();
  });

  $("#open-settings").addEventListener("click", () => settingsDialog.showModal());
  $("#mobile-menu-settings").addEventListener("click", () => {
    mobileMenuDialog.close();
    settingsDialog.showModal();
  });
  $("#refresh").addEventListener("click", loadDashboard);
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
})();
