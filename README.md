# minikun-agent

บริการ AI agent แบบ OpenAI-compatible API ที่พัฒนาด้วย Spring Boot และ Spring AI โดยใช้ Ollama เป็น chat/embedding backend พร้อมระบบจัดการ conversation memory, long-term memory, web search และ runtime diagnostics สำหรับใช้งานภายใน homelab

## ความสามารถหลัก

- OpenAI-compatible Chat Completions API
  - non-streaming JSON response
  - streaming ผ่าน Server-Sent Events (`stream: true`)
- Embeddings API สำหรับข้อความเดี่ยวหรือ array ของข้อความ
- เก็บ short-term conversation history ด้วย Spring AI Chat Memory และ PostgreSQL
- สกัด long-term memory จาก PostgreSQL และเรียกคืนเชิงความหมายด้วย embedding พร้อม lexical fallback
- ประกอบ prompt ผ่าน Provider Composition System (PCS)
- ค้นเว็บผ่าน SearXNG พร้อม cache บน Valkey
- เลือกว่าจะค้นเว็บหรือไม่ผ่าน rule/LLM decision mode
- รวมผล search, explicit URL, image intent และ local context เป็น external-context action ก่อนเรียก Browser/Search
- วางแผน query แบบ deterministic สำหรับตัด conversational wrapper และสร้าง core query ภาษาไทย/อังกฤษ
- ส่ง language/category/time-range/safe-search options ไปยัง SearXNG พร้อม ranking และ URL deduplication
- Actuator health และ metrics
- คำสั่ง runtime และ diagnostics ที่จัดการในระดับ application
- Native function tools: `time.get_current_time`, `weather.get_forecast`, `web.search`, `web.open_url`, `calculator.add`, `planner.manage`, `calendar.manage`, `task.manage`, `homelab.guardian` และ `computer.local`
- ผลลัพธ์จาก tool จะถูกส่งกลับเข้า prompt ของ MCS/PCS เพื่อให้โมเดลตอบต่อด้วยตัวตน บริบท และน้ำเสียงเดิมของมินิคุง
- เก็บ reminder ใน PostgreSQL และส่ง notification ผ่าน ntfy พร้อม daily weather digest เวลา 07:00 (`Asia/Bangkok`)
- เชื่อม private iCalendar feed จาก Google, Apple หรือ Outlook เพื่ออ่าน agenda และเตือนก่อนนัด
- มี proactive safety policy สำหรับ quiet hours และ daily briefing ที่รวมอากาศ นัดหมาย งาน และสิ่งค้างเวลา 08:00 (`Asia/Bangkok`)
- Personal Context Runtime สำหรับ context budget, dynamic max-tokens และ bounded recovery
- ตรวจสอบ ลบรายรายการ และล้าง long-term memory แบบ owner-scoped ผ่าน `/v1/memory`

## เทคโนโลยี

- Java 25
- Spring Boot 4.1.0
- Spring AI 2.0.0
- Maven Wrapper (`./mvnw`)
- Ollama-compatible chat และ embedding model
- cooperative model flow: Ollama รับคำถามก่อน และ TinyGrad ช่วยตรวจ/ปรับคำตอบในคำถามที่ต้องการความแม่นยำ
- PostgreSQL สำหรับ conversation memory และ long-term memory
- Valkey/Redis สำหรับ search cache
- SearXNG สำหรับ web search

## โครงสร้างที่เกี่ยวข้อง

```text
src/main/java/
├── api/                 OpenAI-compatible HTTP API และ conversation ID
├── conversation/        short-term conversation memory
├── memory/              long-term memory และ reflection
├── pcs/                 Provider Composition System สำหรับสร้าง prompt
├── search/              search decision, query processing และ SearXNG
├── commands/            runtime commands
├── context/             Personal Context Runtime และ context snapshots
├── diagnostics/         diagnostics และ metrics
└── runtime/             models, version และ cache information

src/main/resources/
├── application.properties
├── memory-schema.sql
└── logback-spring.xml

docs/
├── conversation-memory.md
└── pcs.md
```

## สิ่งที่ต้องมี

- JDK 25
- PostgreSQL ที่มี database `minikun`
- Ollama หรือ endpoint ที่รองรับ Ollama API
- Valkey/Redis หากเปิด search cache
- SearXNG หากเปิด web search

ใน homelab สามารถเริ่ม dependency หลักจากโฟลเดอร์ workspace ได้ด้วย:

```sh
cd /Volumes/minikun/homelab
docker compose up -d postgres valkey searxng
```

ตรวจสอบว่า Ollama พร้อมใช้งานและมี model ที่ตั้งไว้ใน `OLLAMA_MODEL` หรือ `SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL` แล้ว

## เริ่มรันแบบ local

จากโฟลเดอร์โปรเจกต์:

```sh
cd /Volumes/minikun/homelab/java/minikun_agent
./mvnw spring-boot:run
```

หรือ build และรันไฟล์ JAR:

```sh
./mvnw clean package
java -jar target/minikun_agent-1.0.0.jar
```

ค่าเริ่มต้นของ server คือ `http://127.0.0.1:8080`

ตรวจสอบสถานะ:

```sh
curl http://127.0.0.1:8080/actuator/health
```

Actuator ที่เปิดให้เข้าถึงคือ `/actuator/health` และ `/actuator/metrics` เท่านั้น

## Configuration

ค่าที่ใช้บ่อย:

| ตัวแปร | ค่าเริ่มต้น | รายละเอียด |
|---|---|---|
| `SERVER_PORT` | `8080` | port ของ HTTP server |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://127.0.0.1:5432/minikun` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `minikun` | PostgreSQL user |
| `SPRING_DATASOURCE_PASSWORD` | ว่าง | PostgreSQL password |
| `OLLAMA_BASE_URL` | `http://127.0.0.1:11434` | Ollama endpoint |
| `SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL` | gemma model ใน properties | chat model หลัก |
| `EMBEDDING_MODEL` | `nomic-embed-text` | embedding model |
| `OLLAMA_NUM_CTX` | `16384` | context window ของ Ollama |
| `MINIKUN_MODEL_COOPERATION_ENABLED` | `true` | เปิด Ollama → TinyGrad precision pass |
| `MINIKUN_MODEL_COOPERATION_MODE` | `hybrid` | `hybrid` แสดง Ollama ก่อนแล้วตรวจเบื้องหลัง, `blocking` รอตรวจให้เสร็จก่อนตอบ |
| `MINIKUN_MODEL_COOPERATION_TIMEOUT` | `PT20S` | timeout เฉพาะ TinyGrad verification; timeout แล้ว fallback ตาม mode |
| `MINIKUN_TINYGRAD_MODEL` | `Qwen3.6` | model ที่ TinyGrad ใช้ตรวจ/เสริมคำตอบ |
| `MINIKUN_TINYGRAD_BASE_URL` | `http://localhost:8001/v1` | TinyGrad OpenAI-compatible endpoint |
| `VALKEY_URL` | `redis://127.0.0.1:6379` | Valkey/Redis endpoint |
| `MINIKUN_SEARCH_SEARXNG_URL` | `http://127.0.0.1:8888` | SearXNG endpoint |
| `MINIKUN_SEARCH_TAVILY_ENABLED` | `true` | เปิด/ปิด Tavily provider |
| `MINIKUN_SEARCH_TAVILY_API_KEY` | ว่าง | Tavily API key (เก็บใน environment เท่านั้น) |
| `MINIKUN_SEARCH_TAVILY_URL` | `https://api.tavily.com` | Tavily endpoint |
| `MINIKUN_SEARCH_TAVILY_TIMEOUT` | `10s` | timeout ของ Tavily |
| `MINIKUN_SEARCH_TAVILY_SEARCH_DEPTH` | `basic` | `basic` หรือ `advanced` |
| `MINIKUN_SEARCH_FAILOVER_ENABLED` | `true` | เปิด Tavily → SearXNG failover |
| `MINIKUN_SEARCH_FAILOVER_COOLDOWN` | `PT120S` | ระยะพัก primary หลัง circuit เปิด |
| `MINIKUN_SEARCH_FAILOVER_FAILURE_THRESHOLD` | `3` | จำนวน failure ก่อนเปิด circuit |
| `MINIKUN_SEARCH_ENABLED` | `true` | เปิด/ปิด web search |
| `MINIKUN_WEATHER_ENABLED` | `true` | เปิด/ปิด weather capability |
| `MINIKUN_WEATHER_GEOCODING_URL` | `https://geocoding-api.open-meteo.com` | endpoint สำหรับ resolve สถานที่ |
| `MINIKUN_WEATHER_FORECAST_URL` | `https://api.open-meteo.com` | endpoint สำหรับ forecast |
| `MINIKUN_WEATHER_TIMEOUT` | `10s` | timeout ของ geocoding และ forecast |
| `MINIKUN_TIME_DEFAULT_ZONE` | `Asia/Bangkok` | timezone เริ่มต้นของ `time.get_current_time` |
| `MINIKUN_PLANNER_ENABLED` | `true` | เปิด planner, reminder scheduler และ daily weather notification |
| `MINIKUN_PLANNER_POLL_INTERVAL_MS` | `30000` | รอบตรวจ reminder ที่ถึงเวลาแล้ว |
| `MINIKUN_TASK_ENABLED` | `true` | เปิด goal/task store, tool และ follow-up scheduler |
| `MINIKUN_TASK_POLL_INTERVAL_MS` | `30000` | รอบตรวจ task follow-up ที่ถึงเวลาแล้ว |
| `MINIKUN_TASK_MANAGEMENT_TOKEN` | ใช้ค่า memory token ถ้ามี | token สำหรับ Task API ที่ใช้โดย dashboard/automation |
| `MINIKUN_CALENDAR_EXTERNAL_ENABLED` | `false` | เปิด private iCalendar feed แบบ read-only |
| `MINIKUN_CALENDAR_EXTERNAL_FEED_URL` | ว่าง | private HTTPS `.ics` URL จาก Google, Apple หรือ Outlook |
| `MINIKUN_CALENDAR_EXTERNAL_ZONE` | `Asia/Bangkok` | timezone สำหรับ floating/all-day external events |
| `MINIKUN_CALENDAR_EXTERNAL_CACHE_TTL` | `5m` | อายุ cache ก่อนดาวน์โหลด feed ใหม่ |
| `MINIKUN_CALENDAR_EXTERNAL_REMINDERS_ENABLED` | `true` | ส่ง reminder สำหรับ timed external events |
| `MINIKUN_CALENDAR_EXTERNAL_REMINDERS_BEFORE` | `15m` | ระยะเตือนก่อน external event |
| `MINIKUN_CALENDAR_MANAGEMENT_TOKEN` | ใช้ค่า task/memory token ถ้ามี | token สำหรับ External Calendar API |
| `MINIKUN_PROACTIVE_ENABLED` | `true` | เปิดการแจ้งเตือนที่ agent เริ่มเองทั้งหมด |
| `MINIKUN_PROACTIVE_ZONE` | `Asia/Bangkok` | timezone ที่ใช้คำนวณ quiet hours |
| `MINIKUN_PROACTIVE_QUIET_HOURS_START` | `22:00` | เวลาเริ่มช่วงห้ามรบกวน |
| `MINIKUN_PROACTIVE_QUIET_HOURS_END` | `07:00` | เวลาสิ้นสุดช่วงห้ามรบกวน |
| `MINIKUN_PROACTIVE_BRIEFING_ENABLED` | `true` | เปิด daily briefing งานค้าง |
| `MINIKUN_PROACTIVE_BRIEFING_OWNER_ID` | `default` | owner ที่ใช้สร้าง daily briefing ใน deployment แบบ single-user |
| `MINIKUN_PROACTIVE_BRIEFING_TIME` | `08:00` | เวลาท้องถิ่นที่ส่ง daily briefing |
| `MINIKUN_NTFY_ENABLED` | `true` | เปิด/ปิดการส่ง ntfy |
| `MINIKUN_NTFY_TOKEN` | ว่าง | Bearer token สำหรับ ntfy topic ถ้าตั้ง access control |
| `MINIKUN_NTFY_REMINDER_TOPIC` | topic ที่กำหนดใน `application.properties` | topic สำหรับ reminder |
| `MINIKUN_NTFY_WEATHER_TOPIC` | topic ที่กำหนดใน `application.properties` | topic สำหรับ daily weather |
| `MINIKUN_NOTIFICATION_MANAGEMENT_TOKEN` | ใช้ค่า task/memory token ถ้ามี | token สำหรับอ่านประวัติการส่ง notification |
| `MINIKUN_NOTIFICATION_SCHEDULER_STALE_AFTER` | `2m` | ระยะที่ scheduler ไม่ poll ก่อน health เปลี่ยนเป็น `DOWN` |
| `MINIKUN_NOTIFICATION_SCHEDULER_FAILURE_THRESHOLD` | `3` | จำนวน delivery failure ติดต่อกันก่อน health เปลี่ยนเป็น `DOWN` |
| `MINIKUN_WEATHER_ALERT_LOCATION` | `Bangkok` | สถานที่ของ daily weather digest |
| `MINIKUN_WEATHER_ALERT_ZONE` | `Asia/Bangkok` | timezone ของ daily weather digest |
| `MINIKUN_WEATHER_ALERT_TIME` | `07:00` | เวลาส่ง daily weather digest |

ในโหมด `hybrid` สามารถตรวจผล TinyGrad ตาม `conversation_id` ได้ที่
`GET /v1/cooperation/reviews/{conversation_id}` โดยสถานะจะเป็น `PENDING`,
`COMPLETED` หรือ `FAILED` ผลตรวจนี้ถูกเก็บแยกจาก conversation memory และเป็น in-memory
จึงเหมาะกับ feedback แบบทันทีระหว่าง runtime; หากต้องการ persistence ควรย้าย store ไป PostgreSQL/Valkey ภายหลัง
สำหรับ UI ที่ต้องการรับผลทันทีโดยไม่ polling ให้เปิด SSE ที่
`GET /v1/cooperation/reviews/{conversation_id}/events` โดย stream จะจบเมื่อสถานะเป็น
`COMPLETED` หรือ `FAILED` ตัวอย่าง JavaScript:

```javascript
const events = new EventSource(`/v1/cooperation/reviews/${conversationId}/events`);
events.addEventListener("cooperative-review", event => {
  const review = JSON.parse(event.data);
  if (review.status === "COMPLETED") showRevisedAnswer(review.revised);
  if (review.status === "FAILED") showReviewFailure(review.error);
  if (review.status !== "PENDING") events.close();
});
```

การ route ปัจจุบันใช้กฎแบบเร็ว 3 ระดับ: `LOW` ให้ Ollama ตอบทันที,
`MEDIUM` ให้ Ollama ตอบก่อนแล้ว TinyGrad ตรวจเบื้องหลัง และ `HIGH` รอ TinyGrad
ก่อนส่งคำตอบ เช่น สุขภาพ การเงิน กฎหมาย ความปลอดภัย และข้อมูล credential
คำขอเชิงสร้างสรรค์ เช่น แต่งนิยาย เรื่องสั้น ฟิค roleplay บทกวี และ worldbuilding
จะถูกจัดเป็น `creative_request` และส่งให้ Ollama โดยตรง ไม่ส่งเข้า TinyGrad
| `MINIKUN_SEARCH_CACHE_ENABLED` | `true` | เปิด/ปิด search cache |
| `MINIKUN_SEARCH_CACHE_TTL` | `PT5M` | อายุ search cache |
| `MINIKUN_SEARCH_SAFESEARCH` | `true` | ส่ง safe-search option ให้ SearXNG |
| `MINIKUN_SEARCH_RESULT_LIMIT` | `8` | จำนวนผลลัพธ์ search ต่อ query |
| `MINIKUN_SEARCH_QUERY_PLANNING_ENABLED` | `true` | เปิด query planning และ core keyword extraction |
| `MINIKUN_SEARCH_QUERY_PLANNING_MAX_ALTERNATES` | `2` | จำนวน alternate queries สูงสุด |
| `MINIKUN_SEARCH_CACHE_PROVIDER_VERSION` | `v2` | version ของ provider ที่รวมใน cache key |
| `MINIKUN_SEARCH_PARALLEL_QUERIES_ENABLED` | `true` | ทำ expanded search queries แบบ parallel |
| `MINIKUN_SEARCH_PARALLEL_QUERIES_MAX_CONCURRENCY` | `3` | จำนวน search query สูงสุดที่ทำพร้อมกัน |
| `MINIKUN_SEARCH_DECISION_TIMEOUT` | `PT15S` | timeout เฉพาะ search classifier |
| `MINIKUN_BROWSER_ENABLED` | `true` | เปิด/ปิดการอ่าน URL ผ่าน minikun-browser-worker |
| `MINIKUN_BROWSER_WORKER_URL` | `http://127.0.0.1:3000` | endpoint ของ browser worker |
| `MINIKUN_BROWSER_WORKER_TOKEN` | ว่าง | Bearer token ที่ตรงกับ `BROWSER_WORKER_TOKEN` ของ worker |
| `MINIKUN_BROWSER_TIMEOUT` | `20s` | timeout ของการ render แต่ละ URL |
| `MINIKUN_BROWSER_MAX_URLS` | `5` | จำนวน URL สูงสุดต่อข้อความ |
| `MINIKUN_BROWSER_BLOCK_PRIVATE_ADDRESSES` | `true` | ป้องกัน browser worker เข้าถึง localhost/private network |
| `MINIKUN_BROWSER_MAX_CONTENT_CHARACTERS` | `12000` | ขนาดเนื้อหาสูงสุดต่อ URL ก่อนใส่เข้า Knowledge context |
| `MINIKUN_BROWSER_MAX_CONCURRENCY` | `3` | จำนวน URL ที่ browser worker อ่านพร้อมกัน |
| `SPRING_AI_CHAT_MEMORY_MAX_MESSAGES` | `20` | จำนวนข้อความ short-term memory สูงสุด |
| `MINIKUN_MEMORY_RECALL_MAXIMUM_COUNT` | `10` | จำนวน long-term memories ที่เรียกคืนสูงสุด |
| `MINIKUN_MEMORY_RECALL_MAXIMUM_CHARACTERS` | `4000` | ขนาด Knowledge context สูงสุด |
| `MINIKUN_MEMORY_REFLECTION_ENABLED` | `true` | เปิด memory reflection หลังจบ conversation |
| `MINIKUN_MEMORY_SEMANTIC_ENABLED` | `true` | ใช้ embedding จัดอันดับ long-term memory ตามความหมาย |
| `MINIKUN_MEMORY_SEMANTIC_WEIGHT` | `0.85` | น้ำหนัก semantic similarity เทียบกับ confidence |
| `MINIKUN_DIAGNOSTICS_CONVERSATIONAL_ENABLED` | `false` | เปิด diagnostics แบบ conversational |
| `MINIKUN_CONTEXT_BUDGET_CHARACTERS` | `24000` | character budget สำหรับ prompt context |
| `MINIKUN_TOKEN_BUDGET_RESERVED_OUTPUT_TOKENS` | `256` | output reserve ก่อนคำนวณ dynamic max-tokens |
| `MINIKUN_MEMORY_MANAGEMENT_TOKEN` | ว่าง | token สำหรับป้องกัน API จัดการ memory |

Browser จะอ่านหลาย URL แบบ best-effort: URL ที่อ่านไม่ได้จะถูกบันทึกเป็น failure แต่ URL อื่นยังถูกส่งต่อให้ model ได้ ส่วน URL ที่ชี้ไปยัง localhost หรือ private address จะถูก block โดยค่าเริ่มต้นเพื่อป้องกัน SSRF; หากต้องการเปิด resource ภายในอย่างตั้งใจควรทำ allowlist แยกที่ browser worker/gateway แทนการปิด policy ทั้งหมด

External context planner จะเลือก action ระหว่าง `MEMORY_ONLY`, `OPEN_EXPLICIT_URL`, `SEARCH_WEB`, `SEARCH_THEN_OPEN` และ `IMAGE_SEARCH` ก่อนเรียก external runtime โดย browser content ที่เป็นหน้า error หรือ login/access-blocked จะถูกคัดออกจาก Knowledge context

Search และ Browser มี source-quality gate แบบ conservative สำหรับตรวจหน้า error, access-blocked และข้อความที่มีลักษณะ prompt injection; เนื้อหาที่สั้นหรือข้อมูลน้อยจะถูกติดป้ายคุณภาพต่ำแต่ยังคงไว้ เพื่อไม่ให้ snippet ที่ถูกต้องแต่สั้นถูกทิ้งโดยอัตโนมัติ

ดูค่าทั้งหมดและ default เพิ่มเติมได้ที่ [`application.properties`](src/main/resources/application.properties)

## API

### Chat Completions

```sh
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'X-Conversation-Id: demo-conversation' \
  -d '{
    "model": "minikun",
    "messages": [
      {"role": "user", "content": "สวัสดี"}
    ],
    "stream": false
  }'
```

รองรับฟิลด์หลัก `model`, `messages`, `conversation_id`, `stream`, `temperature`, `max_tokens` และ `max_completion_tokens`

สำหรับ streaming:

```sh
curl -N -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [{"role": "user", "content": "สรุปข่าววันนี้"}],
    "stream": true
  }'
```

### อ่านและสรุปลิงก์

เมื่อข้อความล่าสุดมี HTTP/HTTPS URL ระบบจะเรียก `minikun-browser-worker` แบบ synchronous
แล้วให้โมเดลตอบโดยอ้างอิงจากเนื้อหาที่ render ได้:

```sh
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [{"role": "user", "content": "สรุปบทความนี้ https://example.com/article"}],
    "stream": false
  }'
```

ตั้ง `MINIKUN_BROWSER_WORKER_URL` ให้ agent มองเห็น worker และกำหนด
`MINIKUN_BROWSER_WORKER_TOKEN` เมื่อ worker เปิดใช้ `BROWSER_WORKER_TOKEN`.

ระบบจะส่ง `X-Conversation-Id` กลับมาใน response หาก request ไม่ได้ระบุ conversation ID ระบบจะสร้าง UUID ใหม่ให้โดยอัตโนมัติ ลำดับการเลือก ID คือ:

1. `X-Conversation-Id`
2. `conversation_id` ใน JSON body
3. `X-OpenWebUI-Chat-Id`, `X-Chat-Id` หรือ `Chat-Id`
4. UUID ที่สร้างใหม่

### Models

```sh
curl http://127.0.0.1:8080/v1/models
```

### Embeddings

`input` รับได้ทั้ง string และ array:

```sh
curl -X POST http://127.0.0.1:8080/v1/embeddings \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "nomic-embed-text",
    "input": ["ข้อความแรก", "ข้อความที่สอง"]
  }'
```

### Memory management

สำหรับการตรวจสอบและลบ long-term memory ของ personal agent:

```sh
curl 'http://127.0.0.1:8080/v1/memory?owner_id=default&limit=100'
curl -X DELETE 'http://127.0.0.1:8080/v1/memory/<memory-id>?owner_id=default'
curl -X DELETE 'http://127.0.0.1:8080/v1/memory?owner_id=default'
```

การลบทั้งหมดเป็น owner-scoped และไม่ยอมรับ wildcard owner (`*`) หากตั้งค่า
`MINIKUN_MEMORY_MANAGEMENT_TOKEN` ต้องส่ง header เพิ่ม:

```sh
curl -H "X-Minikun-Memory-Token: $MINIKUN_MEMORY_MANAGEMENT_TOKEN" \
  'http://127.0.0.1:8080/v1/memory?owner_id=default'
```
หากตั้ง `MINIKUN_MEMORY_MANAGEMENT_TOKEN` ต้องส่ง header `X-Minikun-Memory-Token` ทุก request

### Personal user model

ระบบรวม profile, active preferences และ long-term memories ที่ยังอยู่ในอายุการใช้งานไว้เป็น owner-scoped user model สำหรับใช้ประกอบ prompt:

```sh
curl -H "X-Minikun-Memory-Token: $MINIKUN_MEMORY_MANAGEMENT_TOKEN" \
  'http://127.0.0.1:8080/v1/user-model?owner_id=default'
curl -X DELETE -H "X-Minikun-Memory-Token: $MINIKUN_MEMORY_MANAGEMENT_TOKEN" \
  'http://127.0.0.1:8080/v1/user-model?owner_id=default'
```

ค่า `MINIKUN_USER_MODEL_MEMORY_MAX_AGE`, `MINIKUN_USER_MODEL_PREFERENCE_MAX_AGE`,
`MINIKUN_USER_MODEL_MAXIMUM_MEMORIES` และ `MINIKUN_USER_MODEL_MINIMUM_CONFIDENCE`
ใช้ควบคุม lifecycle ของข้อมูลที่ส่งเข้า prompt การแก้ memory รายการเดิมทำได้ผ่าน
`PUT /v1/memory/{id}` หรือ `memory.manage` action `update` โดยยังจำกัดตาม owner เดิม

## Memory และ prompt composition

Short-term history ถูกผูกกับ `ConversationId` และเก็บผ่าน Spring AI JDBC Chat Memory ใน PostgreSQL ส่วน long-term memory ถูกเก็บในตาราง `minikun_memory` ตาม schema ใน [`memory-schema.sql`](src/main/resources/memory-schema.sql)

ใน request chat ระบบจะโหลด history เดิม, เรียกคืน knowledge ที่เกี่ยวข้อง, สร้าง prompt ผ่าน PCS แล้วจึงเรียก chat model หลังตอบสำเร็จจึงบันทึก assistant message กลับเข้า conversation memory

เมื่อมี tool result ที่ยืนยันแล้ว ระบบจะใส่ผลลัพธ์นั้นไว้ใน context ของ prompt และให้โมเดลสร้างคำตอบสุดท้ายเองตาม MCS แทนการส่งข้อความสำเร็จรูปจาก tool โดยตรง

`task.manage` แยกงานค้างและเป้าหมายออกจาก reminder โดยรองรับสถานะ `OPEN`, `IN_PROGRESS`,
`BLOCKED`, `DONE` และ `CANCELLED` รวมถึง `next_action`, `waiting_for`, due date และ follow-up
ที่ส่ง notification ได้ งานที่ยัง active จะถูกนำไปเป็นส่วนหนึ่งของ personal user context เพื่อให้มินิคุง
ต่อบทสนทนาและติดตามงานได้ตรงกับ owner เดิม การเปลี่ยนสถานะหรือการสร้างงานต้องยืนยันก่อนเสมอ
ถ้าข้อความเริ่มต้นด้วยรูปแบบที่ชัดเจน เช่น `ฝากจำ...`, `อย่าลืม...`, `ต้องทำ...` หรือ `todo:`
มินิคุงจะเสนอให้เพิ่มเป็น task อัตโนมัติ แต่จะไม่บันทึกจนกว่าจะได้รับการยืนยัน

Trusted automation สามารถอ่านและจัดการ task ผ่าน `/v1/tasks` โดยส่ง header
`X-Minikun-Task-Token` และระบุ `owner_id` ทุกครั้ง เช่น:

```sh
curl -H "X-Minikun-Task-Token: $MINIKUN_TASK_MANAGEMENT_TOKEN" \
  'http://127.0.0.1:8080/v1/tasks?owner_id=default&status=OPEN'
curl -X POST -H "X-Minikun-Task-Token: $MINIKUN_TASK_MANAGEMENT_TOKEN" \
  'http://127.0.0.1:8080/v1/tasks/<task-id>/complete?owner_id=default'
```

`planner.manage` รองรับ `create`, `list`, `update`, `cancel`, `acknowledge` และ `snooze`
สำหรับ reminder โดยการสร้าง แก้ไข ยกเลิก และ snooze ต้องผ่าน confirmation ก่อนเสมอ
งานที่บันทึกแล้วจะถูกส่งไปยัง ntfy เมื่อถึงเวลา และรายการที่ตั้งซ้ำแบบ `DAILY` หรือ `WEEKLY`
จะเลื่อนรอบถัดไปอัตโนมัติ Explicit reminder ที่ผู้ใช้ตั้งเวลาเองจะส่งตามเวลานั้นแม้ตรงกับ quiet hours

Notification client สามารถรับทราบหรือเลื่อน reminder ผ่าน API ได้ด้วย:

```sh
curl -X POST -H "X-Minikun-Notification-Token: $MINIKUN_NOTIFICATION_MANAGEMENT_TOKEN" \
  'http://127.0.0.1:8080/v1/reminders/<event-id>/acknowledge?conversation_id=<conversation-id>'
curl -X POST -H "Content-Type: application/json" \
  -H "X-Minikun-Notification-Token: $MINIKUN_NOTIFICATION_MANAGEMENT_TOKEN" \
  -d '{"conversationId":"<conversation-id>","until":"2026-08-20T10:30:00+07:00","timezone":"Asia/Bangkok"}' \
  'http://127.0.0.1:8080/v1/reminders/<event-id>/snooze'
```

### External Calendar

เปิดใช้ด้วย private iCalendar URL ผ่านค่ารันไทม์ใน LaunchAgent plist หรือ system environment:

```sh
MINIKUN_CALENDAR_EXTERNAL_ENABLED=true
MINIKUN_CALENDAR_EXTERNAL_FEED_URL=https://calendar-provider.example/private.ics
```

`calendar.manage` action `list` จะรวม local planner กับ external events โดย external calendar เป็น read-only
และรองรับ recurring event, timezone, all-day event รวมถึง reminder ก่อนนัด ระบบจะไม่เขียนกลับไปยัง provider
ดู agenda ล่วงหน้าได้ผ่าน:

```sh
curl -H "X-Minikun-Calendar-Token: $MINIKUN_CALENDAR_MANAGEMENT_TOKEN" \
  'http://127.0.0.1:8080/v1/calendar/events?days=14'
```

Proactive agent ใช้ `minikun.proactive.*` เป็น safety boundary กลาง: ปิดได้ทั้งระบบ,
งดส่งเฉพาะ notification ที่ agent เริ่มเอง เช่น task follow-up, daily briefing และ weather digest ใน quiet hours
ส่วน daily briefing จะรวมสภาพอากาศ นัดหมายจาก local/external calendar งานที่ลงมือได้ และสิ่งที่กำลังรอ
ส่งไม่เกินหนึ่งครั้งต่อวัน และเก็บสถานะการส่งใน PostgreSQL หากแหล่งข้อมูลภายนอกส่วนใดล้ม
briefing จะใช้ข้อมูลส่วนที่เหลือต่อโดยไม่ล้มทั้งฉบับ

### Local Computer Agent

`computer.local` เข้าถึงได้เฉพาะ logical roots ที่กำหนดใน `minikun.computer.roots`
โดย path ทุกค่าต้องเป็น relative path ภายใน root เท่านั้น ค่าเริ่มต้นเปิด `documents` และ `downloads`

- อ่าน/list/search/inspect folder ได้ทันที โดยจำกัด depth, จำนวนไฟล์ และขนาดเนื้อหา
- ปกปิด token, password และ secret ก่อนส่งเนื้อหาเข้า model
- ไม่อ่าน hidden path, environment configuration file, key store หรือ symbolic link ที่ออกนอก root
- write/replace/move/recoverable trash/open app/open URL/fixed workflow ต้อง preview และยืนยันในข้อความถัดไป
- ไม่รองรับ hard delete หรือ shell command จาก model
- application และ workflow ต้องอยู่ใน allowlist ของ `application.properties`
- audit เก็บ operation/target/status แต่ไม่เก็บเนื้อหาไฟล์หรือ clipboard

ผลการส่งทุกครั้งถูกเก็บใน `minikun_notification_delivery` และเรียกดูได้ผ่าน
`GET /v1/notifications` โดยกรอง `source_type`, `source_id`, `status` และ `limit` ได้ เช่น:

```sh
curl -H "X-Minikun-Notification-Token: $MINIKUN_NOTIFICATION_MANAGEMENT_TOKEN" \
  'http://127.0.0.1:8080/v1/notifications?source_type=PLANNER&limit=20'
```

Actuator health รวมสถานะ `notificationSchedulerMonitor` และ metrics กลุ่ม
`minikun.notification.*` สำหรับตรวจว่า scheduler ยัง poll อยู่และมีการส่งล้มเหลวติดต่อกันหรือไม่

รายละเอียดเพิ่มเติม:

- [Conversation memory](docs/conversation-memory.md)
- [Provider Composition System](docs/pcs.md)

## การทดสอบ

รัน test suite:

```sh
./mvnw test
```

การทดสอบ application context ใช้ in-memory chat memory และปิด JDBC persistence บางส่วน จึงไม่จำเป็นต้องมี PostgreSQL สำหรับ unit/context tests ทั้งหมด แต่การทดสอบการทำงานจริงของ Ollama, PostgreSQL, Valkey และ SearXNG ต้องเตรียม service เหล่านั้นเอง

## Deploy บน macOS

สคริปต์ deploy จะ build JAR, ติดตั้ง LaunchAgent และตรวจสอบ health endpoint:

```sh
cd /Volumes/minikun/homelab/java
./minikun_agent/deploy/deploy-minikun-agent.sh
```

สคริปต์ deploy จะสร้างตัวรัน production ไว้ใน `~/Library/Application Support/Minikun/java/script`
และรับค่ารันไทม์จาก `application.properties`, LaunchAgent plist หรือ system environment โดยใช้ค่าหลักดังนี้:

- Java binary: `program/jdk/Contents/Home/bin/java` หรือค่าจาก `JAVA_BIN`
- JAR: `target/minikun_agent-1.0.0.jar`
- profile: `prod`
- heap: `512m` ถึง `2g`
- log: `logs/application.log` และ `logs/transactions.log`

หาก LaunchAgent รันจาก external volume แล้วพบ `Operation not permitted` ให้ตรวจสอบสิทธิ์ Privacy & Security ของ macOS สำหรับ process ที่ launchd เรียกใช้งาน

## หมายเหตุด้าน production

- `spring.sql.init.mode=always` เหมาะกับ local setup แต่ production ควรใช้ migration ที่ควบคุม version ได้
- อย่า commit secret เช่น database password หรือ API key ลง repository ควรส่งผ่าน environment variables
- ตรวจสอบ model name ให้ตรงกับ model ที่ติดตั้งใน Ollama/compatible backend
- การเปิด `MINIKUN_MEMORY_REFLECTION_ENABLED` และ search decision mode แบบ LLM จะเพิ่ม latency และการเรียก model ต่อ request
