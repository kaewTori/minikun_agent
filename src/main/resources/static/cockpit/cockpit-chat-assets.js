(() => {
  "use strict";

  function assetId() {
    try {
      if (globalThis.crypto?.randomUUID) return `asset-${globalThis.crypto.randomUUID()}`;
      if (globalThis.crypto?.getRandomValues) {
        const bytes = new Uint8Array(16);
        globalThis.crypto.getRandomValues(bytes);
        return `asset-${Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("")}`;
      }
    } catch (_) { /* fall through for older Safari and non-secure LAN origins */ }
    return `asset-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  }

  function database() {
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

  async function save(attachment) {
    const asset = {
      id: attachment.assetId || assetId(),
      kind: attachment.kind,
      name: attachment.name,
      type: attachment.type,
      value: attachment.kind === "image" ? attachment.data : attachment.text,
      createdAt: Date.now()
    };
    try {
      const storage = await database();
      await new Promise((resolve, reject) => {
        const transaction = storage.transaction("assets", "readwrite");
        transaction.objectStore("assets").put(asset);
        transaction.oncomplete = resolve;
        transaction.onerror = () => reject(transaction.error);
      });
      storage.close();
      attachment.assetId = asset.id;
    } catch (error) {
      console.warn("Chat attachment persistence unavailable", error);
    }
    return attachment;
  }

  async function load(id) {
    if (!id) return null;
    try {
      const storage = await database();
      const result = await new Promise((resolve, reject) => {
        const request = storage.transaction("assets", "readonly").objectStore("assets").get(id);
        request.onsuccess = () => resolve(request.result || null);
        request.onerror = () => reject(request.error);
      });
      storage.close();
      return result;
    } catch (_) {
      return null;
    }
  }

  globalThis.MinikunChatAssets = Object.freeze({ save, load });
})();
