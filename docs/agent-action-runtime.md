# Agent Action Runtime

มินิคุงรับแผนที่มีขั้นตอนลงมือและเกณฑ์ตรวจผล เก็บ checkpoint ใน PostgreSQL แล้วใช้ worker หนึ่งตัวเดินแผนต่อหลังอนุมัติ โดยใช้ tool registry และ run/step ledger เดิม

## การใช้จาก Cockpit

ไปที่ **Cockpit → Agent → ฝากงานให้มินิคุง** เลือกดูแลบริการ เขียน/ย้ายไฟล์ รัน workflow บันทึกงาน หรือทำงานบนเว็บ รายการที่เปลี่ยน state จะแสดงเป้าหมายและเนื้อหาก่อนให้อนุมัติ

- **อนุมัติและทำต่อ** อนุมัติเฉพาะขั้นที่แสดง แล้ว worker ตรวจผลและเดินแผนที่เหลือ
- **ไม่อนุมัติ / หยุดงาน** ไม่เริ่มขั้นใหม่ คำสั่งที่เริ่มไปแล้วอาจยังทำงานจนเสร็จหรือ timeout
- **ตรวจผลอีกครั้ง** อ่านสถานะปัจจุบันเพื่อยืนยันผลคำสั่งเดิม ไม่มีการสั่ง action ซ้ำ
- **อนุญาตรายการนี้ไว้ล่วงหน้า** ตั้งสิทธิ์เฉพาะ tool/action/resource พร้อมวันหมดอายุ จำนวนครั้ง และ cooldown เพิกถอนได้จากรายการสิทธิ์
- สำหรับ Guardian เลือกติดตามบริการได้ ระบบตรวจทุก 60 วินาทีและเริ่ม repair plan เมื่อ dependency ที่ระบุเป็น `DOWN` ใช้ grant ตามขอบเขตเดิม

งานที่รออนุมัติในบทสนทนาเดียวกันยืนยันด้วย “ยืนยัน” หรือยกเลิกด้วย “ยกเลิก” ได้ หากมีหลายงานรออยู่ให้เลือกใน Cockpit เพื่อระบุงานให้ชัดเจน

หน้า Agent อัปเดตทุก 5 วินาทีเมื่อมองเห็นหน้า แยก “ตรวจผลครบแล้ว” ออกจาก “ยังยืนยันผลไม่ได้” และไม่รีเซ็ตแบบฟอร์มสิทธิ์ที่ผู้ใช้กำลังกรอก

## เครื่องมือที่รองรับ

| งาน | วิธีลงมือ | หลักฐานหลังทำ |
|---|---|---|
| Homelab | Guardian action จาก argv ที่ตั้งไว้ | อ่าน `system.health` ของ dependency ที่ระบุซ้ำสูงสุด 3 ครั้ง |
| เขียนไฟล์ | named root + relative path + hash จาก preview | อ่าน hash ของไฟล์จริง เทียบข้อความที่ขอเขียน |
| ย้ายไฟล์ | preview hash และปลายทางเฉพาะ | เทียบ hash ปลายทางและตรวจว่าต้นทางหายแล้ว |
| ลงถังขยะ | ถังขยะภายใน root เดิม | ตรวจต้นทางหายและรับผล recoverable trash; ผลที่ขาดหายต้องให้ผู้ใช้ตรวจ |
| งานส่วนตัว / reminders | task/planner tools เดิม | read-back validation ใน domain service เดิม |
| พัฒนา / desktop workflow | argv ที่ตั้งไว้ เช่น build/test หรือสคริปต์ควบคุม app | workflow `<id>-verify` ซึ่งเป็น probe แยกที่ผู้ดูแลตั้งให้ตรวจอ่านผล |
| เว็บ | browser profile แยก, snapshot, click/fill/select | อ่าน DOM เป้าหมายหลังทำเทียบ expected text/value หรือ URL |

Browser ใช้รายการเป้าหมายจาก DOM ล่าสุด เลือกปุ่มหรือช่องใน Cockpit ได้ ต้องเปิดเว็บไซต์ใน browser session ก่อนทำงาน หน้าที่เปลี่ยนหลัง preview ถูกหยุดก่อนคลิก/กรอก ช่อง password ให้ล็อกอินด้วยตนเองบน browser ของมินิคุง ไม่ส่ง password/cookie เข้า tool result

Desktop ใช้ app และ fixed workflows ของระบบเดิม สามารถผูกกับสคริปต์ที่ควบคุม app ได้ การควบคุมเมาส์/คีย์บอร์ดอย่างอิสระทั้งจอไม่ใช่ความสามารถของรุ่นนี้

## ตั้งเครื่องเป้าหมาย

เครื่องมืออ่านข้อมูลที่มีอยู่ใช้ได้ตาม configuration เดิม ส่วนคำสั่ง restart/build/test ต้องระบุรายการจริงของเครื่องก่อน ไม่มีคำสั่งจากโมเดลถูกนำไปรันเป็น shell

มี configuration พร้อมทดลองกับ SearXNG ใน homelab นี้ที่ `deploy/agent-actions-homelab.properties` ใช้ action `restart-searxng` กับ component `searxng` จากนั้นโหลดไฟล์ด้วย `--spring.config.additional-location=file:deploy/agent-actions-homelab.properties` การโหลด config ไม่สั่ง restart เอง ยังต้องอนุมัติ action หรือสร้าง grant ผ่านผู้ใช้ก่อน

ตั้ง environment ของ launcher หรือ Spring configuration:

```properties
# ใช้ action_id ที่ตรงกับบริการจริง และ executable แบบ absolute path
minikun.guardian.actions=restart-demo|Restart demo service|/absolute/path/to/service-manager|restart|demo

# เลือก checkout แยกสำหรับงานพัฒนา และพื้นที่เอกสารเฉพาะ
minikun.computer.roots=agent-work=/absolute/path/to/isolated-checkout,agent-docs=/absolute/path/to/documents
minikun.computer.command-timeout=5m

# verifier ต้องตรวจอ่านผลเท่านั้น เช่น ตรวจ test report / build artifact ที่ workflow สร้าง
minikun.computer.workflows=build-test|Build and test checkout|/absolute/path/to/isolated-checkout/mvnw|-f|/absolute/path/to/isolated-checkout/pom.xml|test;build-test-verify|Verify test outcome|/absolute/path/to/read-only-result-probe
```

ค่า environment ที่รองรับคือ `MINIKUN_GUARDIAN_ACTIONS`, `MINIKUN_COMPUTER_ROOTS`, `MINIKUN_COMPUTER_WORKFLOWS`, `MINIKUN_COMPUTER_COMMAND_TIMEOUT`

ชื่อ component ใน repair plan ต้องเป็น key จาก dependencies ของ `system.health` เช่น `ollama` หรือ `postgres` และควรใช้บริการทดลองที่กระทบต่ำก่อน ระบบนี้ไม่ได้ติดตั้งตัวจัดการบริการหรือสร้าง credentials ให้บริการปลายทาง

## API และสิทธิ์

Endpoint อยู่ใต้ `/v1/agent/actions` ต้องใช้ paired device ที่ owner ตรงกัน หรือ `X-Minikun-Agent-Token` ที่กำหนดไว้ และปฏิเสธคำขอข้าม origin/site

| Endpoint | หน้าที่ |
|---|---|
| `GET /`, `GET /{id}` | สถานะ แผน checkpoint และ tool traces |
| `GET /catalog` | tools, roots, workflow และ Guardian actions ที่พร้อมใช้ |
| `POST /repair` | แผน inspect → repair เมื่อจำเป็น → ตรวจ dependency |
| `POST /` | ส่ง explicit plan พร้อม `conversationId`, `idempotencyKey` |
| `POST /{id}/decision` | `{digest, approve}` ต้องตรงกับ preview ที่ยังไม่หมดอายุ |
| `POST /{id}/cancel` | หยุดเริ่มขั้นใหม่ |
| `POST /{id}/review` | อ่านตรวจผลคำสั่งที่ไม่ทราบผล |
| `GET/POST /grants`, `DELETE /grants/{id}` | ดู/สร้าง/เพิกถอนสิทธิ์ที่มีขอบเขต |
| `GET /browser-page?url=...` | อ่านเป้าหมายบนหน้า browser ที่เปิดไว้ |

ตัวอย่างแผนเขียนไฟล์:

```json
{
  "conversationId": "my-conversation",
  "idempotencyKey": "write-report-001",
  "plan": {
    "objective": "บันทึกรายงานในพื้นที่เอกสาร",
    "timeoutSeconds": 600,
    "steps": [{
      "title": "บันทึกรายงาน",
      "tool": "computer.local",
      "arguments": {"action":"write","root":"agent-docs","path":"report.md","content":"# รายงาน\n"}
    }]
  }
}
```

เครื่องมือแชต `agent.action` รองรับ catalog/start/repair/status/cancel โดย approvals และ grants ไม่อยู่ใน schema ของโมเดล แผนทั่วไปส่งขั้น `{title,tool,arguments,verify?,skipIf?}` และ checks `{tool,arguments,pointer,expected}`; `verify` ต้องใช้ read-only tool และค่าที่ตรวจเป็น scalar ส่วน `skipIf` ต้องเป็นเกณฑ์เดียวกับ `verify` เพื่อข้ามเมื่อผลที่ต้องการเกิดขึ้นแล้ว

## Persistence และข้อจำกัด

- migration: `deploy/migrations/V20261004_01__agent_action_runtime.sql`; schema init อยู่ใน `planner-schema.sql`
- ตารางเพิ่ม: `minikun_action_checkpoint` และ `minikun_action_grant` โดยเชื่อมกับ `minikun_agent_run` เดิม
- `@Transactional` ครอบการสร้าง run/checkpoint; checkpoint ใช้ revision เปรียบเทียบก่อนบันทึก และ deduplicate ตาม owner/idempotency key
- grant ใช้ atomic update ตรวจ expiry, revoked state, จำนวนครั้ง และ cooldown ก่อนจองสิทธิ์หนึ่งครั้ง
- consent scope เป็นข้อมูลฝั่ง server ผูก context/tool/arguments; `confirmed=true` จากโมเดลเองถูกแทนด้วยสถานะสิทธิ์จริงที่ executor กลาง
- หลัง restart ถ้าขั้นเดิมอยู่ระหว่าง execute/verify จะอ่านตรวจผลก่อน หากพิสูจน์ไม่ได้จะเป็น `REVIEW_REQUIRED` ไม่มี automatic write replay
- `COMPLETED` ของ action plan หมายถึงครบ observable checks ที่ระบุในแผน ไม่รับรองเป้าหมายกว้าง ๆ ที่ไม่มีเครื่องมือตรวจ; native free-form plans เดิมยังใช้ `UNVERIFIED`
- จำกัด 1–8 plan steps, timeout 10–3600 วินาที, tool budget สูงสุด 50 และ verification probes สูงสุด 3 ครั้ง ไม่มี automatic repair ซ้ำ
- รองรับหนึ่ง server/worker ก่อน deploy หลาย replicas ต้องเพิ่ม database lease เพื่อกัน worker หลายเครื่อง
- Deadline และ cancellation กันการเริ่มขั้นใหม่; command/API ที่เริ่มไปแล้วใช้ timeout ของ adapter นั้น การเพิกถอน grant ไม่ย้อนคำสั่งที่เริ่มไปแล้ว
- แจ้งเมื่อรอผู้ใช้ จบงาน หรือมีปัญหา ใช้ notification runtime เดิม พร้อม source key แยกตาม run/step/event

## ตรวจสอบ

```sh
./mvnw test
node --test src/test/js/*.test.js
PLAYWRIGHT_BROWSERS_PATH="$HOME/Library/Application Support/Minikun/browser/browsers" \
  "$HOME/Library/Application Support/Minikun/browser/venv/bin/python" \
  -m unittest discover -s browser -p 'test*.py'
```

มี Java checks สำหรับ recovery, consent expiry/digest/owner, cancel, exact-resource policy, deadline, file read-back, stale file preview, checkpoint serialization, API auth และ module wiring ส่วน browser checks ใช้หน้าเว็บจำลองใน headless Chromium เพื่อตรวจ click/fill/read-back, DOM เปลี่ยน, URL เปลี่ยน และ password/ambiguous target

ผลนี้ยังไม่ใช่การรับรองการ restart บริการจริงหรือส่งฟอร์มของเว็บไซต์ภายนอก ต้องทดลองกับบริการและ workflow ที่ตั้งไว้บนเครื่องเป้าหมายก่อนเปิดสิทธิ์อัตโนมัติ
