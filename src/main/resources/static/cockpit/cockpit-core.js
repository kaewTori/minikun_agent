((root, factory) => {
  "use strict";

  const core = factory();
  root.MinikunCore = core;
  if (typeof module === "object" && module.exports) module.exports = core;
})(typeof globalThis !== "undefined" ? globalThis : window, () => {
  const TOKEN_HEADERS = [
    "X-Minikun-Personal-Token",
    "X-Minikun-Task-Token",
    "X-Minikun-Goal-Token",
    "X-Minikun-Agent-Token",
    "X-Minikun-Memory-Token",
    "X-Minikun-Knowledge-Token",
    "X-Minikun-System-Token",
    "X-Minikun-Model-Token"
  ];

  function uniqueId(prefix = "") {
    try {
      if (globalThis.crypto?.randomUUID) return `${prefix}${globalThis.crypto.randomUUID()}`;
      if (globalThis.crypto?.getRandomValues) {
        const bytes = new Uint8Array(16);
        globalThis.crypto.getRandomValues(bytes);
        return `${prefix}${Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("")}`;
      }
    } catch (_) { /* fall through for older browsers and non-secure LAN origins */ }
    return `${prefix}${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  }

  function textContent(value) {
    if (Array.isArray(value)) {
      return value.filter((part) => part?.type === "text").map((part) => part.text || "").join("\n");
    }
    return String(value ?? "");
  }

  function investmentReplySymbol(value, baseUrl, canonicalOrigin = baseUrl) {
    try {
      const url = new URL(value, baseUrl);
      const origins = new Set([new URL(baseUrl).origin, new URL(canonicalOrigin).origin]);
      if (!origins.has(url.origin) || !["http:", "https:"].includes(url.protocol)
          || url.username || url.password || !["/cockpit/", "/cockpit/index.html"].includes(url.pathname)) return "";
      const symbol = url.searchParams.get("reply_symbol") || "";
      return /^[A-Z][A-Z0-9.-]{0,7}$/.test(symbol) ? symbol : "";
    } catch (_) { return ""; }
  }

  function investmentReplyDraft(symbol) {
    return /^[A-Z][A-Z0-9.-]{0,7}$/.test(symbol)
      ? `เหตุผลที่ถือ ${symbol}: \nทบทวนเมื่อ: ` : "";
  }

  function normalizeMessage(value = {}) {
    const metadata = value.metadata || {};
    return {
      id: String(value.id || uniqueId("message-")),
      role: ["user", "assistant", "system"].includes(value.role) ? value.role : "user",
      content: textContent(value.content),
      createdAt: Number(value.createdAt) || Date.parse(value.createdAt || "") || Date.now(),
      localOnly: Boolean(value.localOnly),
      responseId: value.responseId || metadata.responseId || "",
      parentId: value.parentId || metadata.parentId || "",
      branchId: value.branchId || metadata.branchId || "",
      status: value.status || metadata.status || "complete",
      finishReason: value.finishReason || metadata.finishReason || "",
      feedback: value.feedback || metadata.feedback || "",
      feedbackCategory: value.feedbackCategory || metadata.feedbackCategory || "",
      feedbackReason: value.feedbackReason || metadata.feedbackReason || "",
      backgroundJobId: value.backgroundJobId || metadata.backgroundJobId || "",
      files: Array.isArray(value.files) ? value.files.slice(0, 12) : [],
      attachments: Array.isArray(value.attachments) ? value.attachments.slice(0, 12) : [],
      usage: value.usage,
      timing: value.timing || undefined,
      sources: Array.isArray(value.sources) ? value.sources.slice(0, 12)
        : Array.isArray(metadata.sources) ? metadata.sources.slice(0, 12) : []
    };
  }

  function normalizeConversations(values) {
    return (Array.isArray(values) ? values : [])
      .slice(0, 40)
      .filter((value) => value?.id)
      .map((value) => ({
        id: String(value.id),
        title: String(value.title || "แชตใหม่"),
        updatedAt: Number(value.updatedAt) || Date.parse(value.updatedAt) || Date.now(),
        pinned: Boolean(value.pinned),
        archived: Boolean(value.archived),
        messages: (Array.isArray(value.messages) ? value.messages : [])
          .slice(-80)
          .map(normalizeMessage)
      }))
      .sort((left, right) => Number(right.pinned) - Number(left.pinned) || right.updatedAt - left.updatedAt);
  }

  function compactMessages(messages, limit = 80) {
    return (Array.isArray(messages) ? messages : []).slice(-limit).map((message) => ({
      ...normalizeMessage(message),
      attachments: (message.attachments || [])
        .map((attachment) => ({
          title: attachment.title || attachment.name || "ไฟล์แนบ",
          url: attachment.url && !attachment.url.startsWith("data:") ? attachment.url : "",
          originalUrl: attachment.originalUrl || attachment.original_url || attachment.remoteUrl || "",
          sourceUrl: attachment.sourceUrl || attachment.source_url || "",
          description: attachment.description || "",
          origin: attachment.origin || "",
          provider: attachment.provider || "",
          license: attachment.license || "",
          prompt: attachment.prompt || "",
          negativePrompt: attachment.negativePrompt || attachment.negative_prompt || "",
          seed: attachment.seed ?? null,
          generationId: attachment.generationId || attachment.generation_id || "",
          filename: attachment.filename || "",
          contentType: attachment.contentType || attachment.content_type || "",
          sizeBytes: attachment.sizeBytes ?? attachment.size_bytes ?? null,
          slideCount: attachment.slideCount ?? attachment.slide_count ?? null,
          artifactId: attachment.artifactId || attachment.artifact_id || "",
          assetId: attachment.assetId || "",
          kind: attachment.kind || "image",
          type: attachment.type || ""
        }))
        .filter((attachment) => attachment.url || attachment.assetId)
        .slice(0, 6)
    }));
  }

  function loadConversations(key = "minikun.conversations") {
    try {
      return normalizeConversations(JSON.parse(localStorage.getItem(key) || "[]"));
    } catch (_) {
      return [];
    }
  }

  function conversationTitle(seed) {
    const clean = String(seed || "แชตใหม่").replace(/\s+/g, " ").trim();
    return clean.length > 42 ? `${clean.slice(0, 42)}…` : clean || "แชตใหม่";
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
          responseId: message.responseId || "",
          branchId: message.branchId || "",
          status: message.status || "complete",
          finishReason: message.finishReason || "",
          feedback: message.feedback || "",
          feedbackCategory: message.feedbackCategory || "",
          feedbackReason: message.feedbackReason || "",
          backgroundJobId: message.backgroundJobId || "",
          sources: (message.sources || []).slice(0, 12)
        },
        createdAt: new Date(message.createdAt || Date.now()).toISOString()
      }))
    };
  }

  function authHeaders(token = "", json = false) {
    const headers = json ? { "Content-Type": "application/json" } : {};
    if (token) TOKEN_HEADERS.forEach((name) => { headers[name] = token; });
    return headers;
  }

  function withOwner(path, ownerId = "default") {
    const url = new URL(path, globalThis.location?.origin || "http://localhost");
    url.searchParams.set("ownerId", ownerId);
    url.searchParams.set("owner_id", ownerId);
    return `${url.pathname}${url.search}${url.hash}`;
  }

  async function readResponse(response) {
    if (response.status === 204) return null;
    const type = response.headers.get("content-type") || "";
    return type.includes("json") ? response.json() : response.text();
  }

  function requestOptions(options = {}) {
    const request = { ...options };
    if (Object.prototype.hasOwnProperty.call(request, "json")) {
      request.body = JSON.stringify(request.json);
      delete request.json;
    }
    return request;
  }

  function responseMessage(body, fallback) {
    return String(body?.error?.message || body?.message || body?.detail
      || (typeof body?.error === "string" ? body.error : body) || fallback);
  }

  async function request(path, options = {}, identity = {}) {
    const request = requestOptions(options);
    const response = await fetch(withOwner(path, identity.ownerId || "default"), {
      ...request,
      headers: { ...authHeaders(identity.token, typeof request.body === "string"), ...(request.headers || {}) }
    });
    if (!response.ok) {
      const body = await readResponse(response).catch(() => null);
      const error = new Error(responseMessage(body, `เชื่อมต่อไม่สำเร็จ (${response.status})`));
      error.status = response.status;
      throw error;
    }
    return readResponse(response);
  }

  async function download(path, identity = {}) {
    const response = await fetch(withOwner(path, identity.ownerId || "default"), {
      headers: authHeaders(identity.token)
    });
    if (!response.ok) {
      const body = await readResponse(response).catch(() => null);
      const error = new Error(responseMessage(body, `ดาวน์โหลดไม่สำเร็จ (${response.status})`));
      error.status = response.status;
      throw error;
    }
    return response.blob();
  }

  async function syncRequest(path, options = {}, identity = {}) {
    const request = requestOptions(options);
    const response = await fetch(path, {
      ...request,
      headers: { ...authHeaders(identity.token, typeof request.body === "string"), ...(request.headers || {}) }
    });
    if (!response.ok) {
      const body = await readResponse(response).catch(() => null);
      const error = new Error(responseMessage(body, `ซิงก์ไม่สำเร็จ (${response.status})`));
      error.status = response.status;
      throw error;
    }
    return readResponse(response);
  }

  function defaultDeviceName() {
    const userAgent = globalThis.navigator?.userAgent || "";
    const platform = globalThis.navigator?.platform || "";
    if (/iPhone/i.test(userAgent)) return "iPhone";
    if (/iPad/i.test(userAgent)) return "iPad";
    if (/Mac/i.test(platform || userAgent)) return "Mac เครื่องหลัก";
    return "Browser เครื่องนี้";
  }

  function wavBlob(chunks, sourceRate) {
    // ponytail: keep one small WAV encoder until browser recording moves to a native codec.
    const sourceLength = chunks.reduce((total, chunk) => total + chunk.length, 0);
    const source = new Float32Array(sourceLength);
    let sourceOffset = 0;
    for (const chunk of chunks) {
      source.set(chunk, sourceOffset);
      sourceOffset += chunk.length;
    }
    const targetRate = Math.min(16000, sourceRate);
    const ratio = sourceRate / targetRate;
    const samples = new Float32Array(Math.floor(source.length / ratio));
    for (let index = 0; index < samples.length; index += 1) {
      const start = Math.floor(index * ratio);
      const end = Math.min(source.length, Math.floor((index + 1) * ratio));
      let total = 0;
      for (let cursor = start; cursor < end; cursor += 1) total += source[cursor];
      samples[index] = total / Math.max(1, end - start);
    }
    const buffer = new ArrayBuffer(44 + samples.length * 2);
    const view = new DataView(buffer);
    const ascii = (offset, value) => {
      for (let index = 0; index < value.length; index += 1) view.setUint8(offset + index, value.charCodeAt(index));
    };
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

  return Object.freeze({
    uniqueId,
    textContent,
    investmentReplySymbol,
    investmentReplyDraft,
    normalizeMessage,
    normalizeConversations,
    compactMessages,
    loadConversations,
    conversationTitle,
    syncPayload,
    authHeaders,
    withOwner,
    readResponse,
    request,
    download,
    syncRequest,
    defaultDeviceName,
    wavBlob
  });
});
