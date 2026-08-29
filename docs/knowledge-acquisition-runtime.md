# Self-learning Knowledge Acquisition Runtime

ระบบนี้ทำให้มินิคุงสะสมความรู้จากภายนอกอย่างต่อเนื่องโดยไม่ฝึกน้ำหนักโมเดลใหม่ ความรู้ถูกเก็บเป็น claim ที่มีหลักฐาน สถานะ ความเชื่อมั่น และวันหมดอายุ แล้วเรียกคืนร่วมกับ Personal Knowledge ขณะตอบแชต

## วงจรการทำงาน

```text
Knowledge Topic
      │ scheduled/manual
      ▼
plan → search → open original pages → extract claims
                                      │
                                      ▼
                            deterministic verification
                              │                    │
                         candidate            published
                              │                    │
                         human review      hybrid chat retrieval
                                                   │
                                             stale/refresh
```

หนึ่ง `Knowledge Topic` ระบุ objective, priority, refresh policy, source policy และ trusted domains ไว้อย่างชัดเจน Scheduler เลือกเฉพาะหัวข้อ `ACTIVE` ที่ถึงกำหนด และรันแบบ bounded ตาม timeout, query budget และ source-read limit ของ Autonomous Research

Acquisition run เก็บ audit trace, stop reason, จำนวน source/claim, error และเวลาเริ่มจบ ส่วน source ledger เก็บ URL, ชนิดแหล่งข้อมูล, excerpt และ content hash เพื่อรักษา provenance และ deduplicate ข้อมูล

## Publication gate

ระบบไม่ยอมให้ความมั่นใจจากโมเดลเพียงอย่างเดียวเผยแพร่ความรู้:

- Search snippet อย่างเดียวเป็นได้เพียง `CANDIDATE`; ต้องเปิดอ่านต้นฉบับผ่าน Browser Worker
- Claim จาก trusted domain เผยแพร่ได้เมื่อ confidence ผ่านเกณฑ์
- Claim จากโดเมนทั่วไปต้องมีเอกสารที่เปิดอ่านแล้วอย่างน้อยสองโดเมนอิสระ
- `OFFICIAL_ONLY` รับเฉพาะ trusted domains ที่ตั้งใน topic
- Extraction fallback, ข้อความลักษณะ prompt injection และข้อมูลหมดอายุจะไม่เข้า chat retrieval
- ผู้ดูแลเลื่อน claim เป็น `PUBLISHED`, `CANDIDATE`, `DISPUTED` หรือ `RETRACTED` ได้เอง

Claim ที่เผยแพร่จะมีอายุเป็นสองเท่าของ refresh interval; topic แบบ `MANUAL` มีอายุ 90 วัน เมื่อหมดอายุ claim จะเป็น `STALE` จนกว่าจะถูกค้นและยืนยันใหม่

## แหล่งค้นหา

Agent ใช้ research stack เดิมของมินิคุง:

1. Tavily เมื่อเปิดใช้และมี API key
2. SearXNG เป็น fallback หรือเป็น provider หลักเมื่อไม่มี Tavily
3. Browser Worker เปิดหน้าเว็บจริงและคัดหน้า error, access block และ prompt injection
4. Task model สกัด atomic claims จาก evidence ที่อ่านมา
5. Embedding model ช่วย semantic retrieval; หาก embedding ใช้ไม่ได้จะ fallback เป็น lexical retrieval

ดังนั้นระบบขั้นต่ำที่ต้องเตรียมคือ PostgreSQL, Ollama/task model, SearXNG และ Browser Worker ส่วน Tavily เป็นทางเลือก ถ้า Browser Worker ไม่พร้อม agent ยังเก็บ candidate จาก snippet ได้ แต่จะไม่เผยแพร่โดยอัตโนมัติ

## Management API

ทุก endpoint อยู่ใต้ `/v1/knowledge/acquisition` และใช้ header `X-Minikun-Knowledge-Token` เมื่อกำหนด management token

สร้างหัวข้อ:

```sh
curl -X POST http://localhost:8080/v1/knowledge/acquisition/topics \
  -H 'Content-Type: application/json' \
  -H "X-Minikun-Knowledge-Token: $MINIKUN_KNOWLEDGE_ACQUISITION_TOKEN" \
  -d '{
    "owner_id": "default",
    "name": "Spring AI releases",
    "objective": "Track stable Spring AI releases, migrations, and breaking changes",
    "priority": 80,
    "refresh_policy": "WEEKLY",
    "source_policy": "OFFICIAL_FIRST",
    "trusted_domains": ["spring.io", "docs.spring.io"],
    "status": "ACTIVE"
  }'
```

สั่งรันทันทีและดูผล:

```sh
curl -X POST 'http://localhost:8080/v1/knowledge/acquisition/topics/{topic-id}/runs?owner_id=default' \
  -H "X-Minikun-Knowledge-Token: $MINIKUN_KNOWLEDGE_ACQUISITION_TOKEN"

curl 'http://localhost:8080/v1/knowledge/acquisition/claims?owner_id=default&status=CANDIDATE' \
  -H "X-Minikun-Knowledge-Token: $MINIKUN_KNOWLEDGE_ACQUISITION_TOKEN"
```

ตรวจและเผยแพร่ claim ด้วยคน:

```sh
curl -X PATCH http://localhost:8080/v1/knowledge/acquisition/claims/{claim-id} \
  -H 'Content-Type: application/json' \
  -H "X-Minikun-Knowledge-Token: $MINIKUN_KNOWLEDGE_ACQUISITION_TOKEN" \
  -d '{"owner_id":"default","status":"PUBLISHED"}'
```

ค่า enum ที่ใช้ได้:

- `refresh_policy`: `HOURLY`, `DAILY`, `WEEKLY`, `MONTHLY`, `MANUAL`
- `source_policy`: `OFFICIAL_ONLY`, `OFFICIAL_FIRST`, `BALANCED`
- `status` ของ topic: `ACTIVE`, `PAUSED`
- `origin`: `SUBSCRIBED`, `INTEREST_INFERRED`, `KNOWLEDGE_GAP`, `SYSTEM_DEPENDENCY`

รุ่นปัจจุบันสร้าง topic ผ่าน management API ก่อนเพื่อให้ขอบเขตและต้นทุนชัดเจน ค่า `origin` รองรับหัวข้อที่ component อื่นอนุมานในอนาคต แต่ยังไม่สร้างหัวข้อใหม่จากทุกบทสนทนาโดยอัตโนมัติ

## Data and operations

ตาราง PostgreSQL คือ `minikun_knowledge_topic`, `minikun_knowledge_acquisition_run`, `minikun_external_source` และ `minikun_knowledge_claim` โดย schema ถูกโหลดจาก `knowledge-acquisition-schema.sql`; production migration อยู่ที่ `deploy/migrations/V20260829_01__knowledge_acquisition.sql`

ควรตั้ง management token, เริ่มด้วย `topics-per-run=1`, ใช้ trusted domains กับหัวข้อสำคัญ และตรวจรายการ `CANDIDATE`/`DISPUTED` เป็นระยะ การลบ topic จะลบ run, source และ claim ของ topic นั้นผ่าน foreign-key cascade
