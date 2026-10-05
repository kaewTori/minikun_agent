(() => {
  "use strict";
  const labels = { PLANNED: "เตรียมงานแล้ว", RUNNING: "กำลังทำ", WAITING_CONFIRMATION: "รอยืนยัน",
    COMPLETED: "ตรวจผลครบแล้ว", UNVERIFIED: "ยังยืนยันผลไม่ได้", REVIEW_REQUIRED: "ต้องตรวจผลก่อนทำต่อ",
    FAILED: "มีปัญหา", LIMIT_REACHED: "ถึงขีดจำกัด", CANCELLED: "ยกเลิกแล้ว" };
  function node(tag, text, className = "") {
    const el = document.createElement(tag); if (text !== undefined) el.textContent = text;
    el.className = className; return el;
  }
  function create({ api, toast, refresh, conversation }) {
    const form = document.querySelector("#action-start-form");
    if (!form) return null;
    const fields = document.querySelector("#action-fields");
    const kind = document.querySelector("#action-kind");
    const error = document.querySelector("#action-form-error");
    let catalog = {}, lastKind = "", requestKey = "", renderKey = "";
    function field(name, title, type = "text", choices) {
      const label = node("label", title); const input = node(choices ? "select" : type === "textarea" ? "textarea" : "input");
      input.id = `action-${name}`; input.name = name; input.required = true;
      if (choices) {
        for (const [value, text] of choices) { const option = node("option", text); option.value = value; input.append(option); }
      } else if (type === "textarea") input.rows = 4; else input.type = type;
      if (name === "content") input.maxLength = 65536;
      label.htmlFor = input.id; label.append(input); fields.append(label); return input;
    }
    function configure(force = false) {
      if (!force && kind.value === lastKind) return;
      lastKind = kind.value; fields.replaceChildren(); error.textContent = ""; requestKey = "";
      if (kind.value === "repair") {
        field("component", "บริการที่ต้องการดูแล", "text", catalog.components?.length ? catalog.components.map(name => [name, name]) : undefined);
        field("actionId", "วิธีแก้ที่ตั้งไว้", "text", (catalog.guardianActions || []).map(a => [a.action_id, a.description]));
        if (!(catalog.guardianActions || []).length) error.textContent = "ยังไม่มีวิธีแก้ที่ตั้งไว้สำหรับเครื่องนี้ครับ ตั้งรายการคำสั่งก่อน แล้วกดอัปเดตงาน";
      } else if (["write", "move", "trash"].includes(kind.value)) {
        field("root", "พื้นที่ไฟล์", "text", (catalog.roots || []).map(r => [r.name, r.name]));
        field("path", "ชื่อไฟล์ในพื้นที่นี้");
        if (kind.value === "write") field("content", "ข้อความที่จะเขียน", "textarea");
        if (kind.value === "move") field("target", "ชื่อไฟล์ปลายทาง");
      } else if (kind.value === "workflow") {
        field("workflow_id", "งานที่ตั้งไว้", "text", (catalog.workflows || []).filter(w => !w.id.endsWith("-verify")).map(w => [w.id, w.description]));
      } else if (kind.value === "task") {
        field("title", "ชื่องาน");
      } else {
        field("url", "เว็บไซต์", "url");
        field("action", "สิ่งที่ให้ทำ", "text", [["click", "กดปุ่ม"], ["fill", "กรอกข้อความ"], ["select", "เลือกค่า"]]);
        const target = field("selector", "ช่องหรือปุ่มเป้าหมาย", "text", []);
        const read = node("button", "เปิดเว็บบน Mac และอ่านเป้าหมาย", "secondary-button"); read.type = "button";
        fields.append(read);
        read.addEventListener("click", async () => {
          read.disabled = true; error.textContent = "";
          try {
            const url = form.elements.url.value;
            await api("/v1/browser/session/open", { method: "POST", body: JSON.stringify({ url }) });
            const page = await api(`/v1/agent/actions/browser-page?url=${encodeURIComponent(url)}`);
            target.replaceChildren(); form.elements.checkSelector.replaceChildren();
            for (const control of page.controls || []) {
              if (!control.selector) continue;
              const option = node("option", control.label || control.name || control.tag); option.value = control.selector;
              form.elements.checkSelector.append(option.cloneNode(true));
              if (["button", "a", "input", "textarea", "select"].includes(control.tag)) target.append(option);
            }
            if (page.url) form.elements.url.value = page.url;
            toast("อ่านเป้าหมายจากหน้าเว็บแล้วครับ");
          } catch (e) { error.textContent = e.message; } finally { read.disabled = false; }
        });
        const value = field("value", "ข้อความหรือค่าที่เลือก"); value.required = false;
        field("checkSelector", "ข้อความหรือช่องสำหรับตรวจผล", "text", []);
        field("expected", "ผลที่ต้องการเห็นหลังทำ");
      }
    }
    kind.addEventListener("change", () => configure());
    form.addEventListener("input", () => { requestKey = ""; });
    document.querySelector("#action-refresh")?.addEventListener("click", refresh);
    form.addEventListener("submit", async event => {
      event.preventDefault(); const button = document.querySelector("#action-start");
      const args = Object.fromEntries(new FormData(form));
      requestKey ||= crypto.randomUUID(); error.textContent = "";
      button.disabled = true; form.setAttribute("aria-busy", "true");
      try {
        let path = "/v1/agent/actions", body;
        if (kind.value === "repair") {
          path += "/repair"; body = { ...args, conversationId: conversation(), idempotencyKey: requestKey };
        } else {
          let tool = "computer.local", action = kind.value, verify;
          if (kind.value === "task") { tool = "task.manage"; action = "create"; }
          if (kind.value === "browser") {
            tool = "browser.control"; action = args.action;
            verify = { tool, arguments: { action: "snapshot", url: args.url, selector: args.checkSelector },
              pointer: action === "fill" || action === "select" ? "/selectedValue" : "/selectedText", expected: args.expected };
            delete args.checkSelector; delete args.expected;
          }
          body = { conversationId: conversation(), idempotencyKey: requestKey,
            plan: { objective: kind.options[kind.selectedIndex].textContent, timeoutSeconds: 600,
              steps: [{ title: kind.options[kind.selectedIndex].textContent, tool, arguments: { ...args, action }, ...(verify ? { verify } : {}) }] } };
        }
        await api(path, { method: "POST", body: JSON.stringify(body) });
        requestKey = ""; toast("รับงานแล้วครับ"); await refresh();
      } catch (e) { error.textContent = e.message; }
      finally { button.disabled = false; form.removeAttribute("aria-busy"); }
    });
    function button(parent, title, run, operation, body) {
      const b = node("button", title, body?.approve ? "primary-button" : "secondary-button"); b.type = "button";
      b.addEventListener("click", async () => {
        b.disabled = true; b.setAttribute("aria-busy", "true");
        try { await api(`/v1/agent/actions/${run.id}/${operation}`, { method: "POST", ...(body ? { body: JSON.stringify(body) } : {}) }); await refresh(); }
        catch (e) { toast(e.message, true); }
        finally { b.disabled = false; b.removeAttribute("aria-busy"); }
      }); parent.append(b);
    }
    function grantForm(row, item) {
      const detail = node("details"); detail.append(node("summary", "อนุญาตรายการนี้ไว้ล่วงหน้า"));
      const f = node("form", undefined, "action-grant-form");
      for (const [name, title, value, max] of [["hours", "หมดอายุในกี่ชั่วโมง", 24, 720], ["maxUses", "จำนวนครั้งสูงสุด", 1, 1000], ["cooldownSeconds", "เว้นระหว่างครั้ง (วินาที)", 3600, 86400]]) {
        const label = node("label", title), input = node("input"); input.type = "number"; input.name = name; input.value = value;
        input.min = name === "cooldownSeconds" ? 0 : 1; input.max = max; input.required = true; label.append(input); f.append(label);
      }
      const monitor = node("input"); monitor.type = "checkbox"; monitor.name = "monitor";
      if (item.permission?.tool === "homelab.guardian") { const label = node("label", "ตรวจบริการนี้และใช้สิทธิ์เมื่อพบปัญหา"); label.append(monitor); f.append(label); }
      const submit = node("button", "อนุญาตเฉพาะรายการนี้", "secondary-button"); submit.type = "submit"; f.append(submit);
      f.addEventListener("submit", async e => {
        e.preventDefault(); submit.disabled = true;
        const values = Object.fromEntries(new FormData(f));
        const step = item.checkpoint.plan.steps[item.checkpoint.nextStep];
        const component = step.verify?.pointer?.match(/^\/dependencies\/([^/]+)\/status$/)?.[1] || "";
        try {
          await api("/v1/agent/actions/grants", { method: "POST", body: JSON.stringify({ ...item.permission,
            expiresAt: new Date(Date.now() + Number(values.hours) * 3600000).toISOString(), maxUses: Number(values.maxUses),
            cooldownSeconds: Number(values.cooldownSeconds), monitorComponent: monitor.checked ? component : "" }) });
          toast("บันทึกสิทธิ์แล้วครับ งานนี้ยังรอการยืนยันตามรายการเดิม"); await refresh();
        } catch (err) { toast(err.message, true); } finally { submit.disabled = false; }
      }); detail.append(f); row.append(detail);
    }
    function render(items = [], grants = [], nextCatalog = {}) {
      const catalogChanged = JSON.stringify(catalog) !== JSON.stringify(nextCatalog);
      catalog = nextCatalog; if (!lastKind || catalogChanged && !fields.querySelector("input")?.value) configure(true);
      const list = document.querySelector("#action-runtime-list");
      const key = JSON.stringify([items, grants]);
      if (key === renderKey || list.contains(document.activeElement) && ["INPUT", "SELECT", "TEXTAREA"].includes(document.activeElement.tagName)) return;
      renderKey = key; list.replaceChildren();
      if (!items.length) list.append(node("p", "ยังไม่มีงานที่ฝากให้มินิคุงครับ", "empty-state"));
      for (const item of items) {
        const { run, checkpoint: c } = item;
        const row = node("article", undefined, "agent-run action-run"), heading = node("div", undefined, "agent-run-heading");
        heading.append(node("strong", run.objective), node("span", labels[run.status] || run.status, "run-status")); row.append(heading);
        row.append(node("p", `${c.nextStep}/${c.plan.steps.length} ขั้นที่ตรวจแล้ว · ${run.summary || "กำลังเตรียมงาน"}`));
        const steps = node("ol", undefined, "action-plan-steps");
        c.plan.steps.forEach((step, index) => {
          const li = node("li", `${index < c.nextStep ? "ตรวจแล้ว · " : index === c.nextStep ? "ขั้นปัจจุบัน · " : "รอ · "}${step.title}`);
          if (c.evidence?.[index]?.result) li.append(node("small", c.evidence[index].result)); steps.append(li);
        }); row.append(steps);
        const current = c.plan.steps[c.nextStep];
        if (run.status === "WAITING_CONFIRMATION") {
          const preview = node("div", undefined, "action-preview");
          for (const [key, title] of [["action_id", "รายการ"], ["root", "พื้นที่"], ["path", "ไฟล์"], ["target", "ปลายทาง"], ["workflow_id", "งาน"], ["url", "เว็บไซต์"], ["selector", "เป้าหมาย"], ["value", "ค่าที่จะกรอก"]])
            if (c.prepared[key]) preview.append(node("p", `${title}: ${c.prepared[key]}`));
          if (c.prepared.content !== undefined) { preview.append(node("strong", "ข้อความที่จะเขียน"), node("pre", c.prepared.content)); }
          preview.append(node("p", `อนุมัติได้ถึง ${new Date(c.consentExpiresAt).toLocaleString("th-TH")}`)); row.append(preview);
          const controls = node("div", undefined, "item-controls");
          button(controls, "อนุมัติและทำต่อ", run, "decision", { digest: c.digest, approve: true });
          button(controls, "ไม่อนุมัติ", run, "decision", { digest: c.digest, approve: false }); row.append(controls);
          if (item.permission?.resource) grantForm(row, item);
        } else if (["PLANNED", "RUNNING"].includes(run.status)) button(row, "หยุดงาน", run, "cancel");
        else if (["REVIEW_REQUIRED", "UNVERIFIED"].includes(run.status)) button(row, "ตรวจผลอีกครั้ง", run, "review");
        const trace = node("details"); trace.append(node("summary", "ดูหลักฐานแต่ละขั้น"));
        for (const step of item.steps || []) trace.append(node("p", `${step.stepIndex}. ${step.toolName} · ${step.status}${step.error ? ` · ${step.error}` : ""}`));
        row.append(trace); list.append(row);
      }
      const grantList = document.querySelector("#action-grant-list"); grantList.replaceChildren();
      if (!grants.length) grantList.append(node("p", "ยังไม่ได้อนุญาตงานใดไว้ล่วงหน้าครับ", "empty-state"));
      for (const grant of grants) {
        const row = node("div", undefined, "action-grant-row");
        row.append(node("p", `${grant.resource} · ใช้แล้ว ${grant.uses}/${grant.maxUses} ครั้ง · หมดอายุ ${new Date(grant.expiresAt).toLocaleString("th-TH")}`));
        const revoke = node("button", "เพิกถอน", "text-button"); revoke.type = "button";
        revoke.addEventListener("click", async () => { revoke.disabled = true;
          try { await api(`/v1/agent/actions/grants/${grant.id}`, { method: "DELETE" }); await refresh(); }
          catch (e) { toast(e.message, true); } finally { revoke.disabled = false; }
        }); row.append(revoke); grantList.append(row);
      }
    }
    configure(); return { render };
  }
  window.MinikunActions = { create };
})();
