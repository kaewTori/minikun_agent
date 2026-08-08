# minikun-agent

บริการ AI agent แบบ OpenAI-compatible API ที่พัฒนาด้วย Spring Boot และ Spring AI โดยใช้ Ollama เป็น chat/embedding backend พร้อมระบบจัดการ conversation memory, long-term memory, web search และ runtime diagnostics สำหรับใช้งานภายใน homelab

## ความสามารถหลัก

- OpenAI-compatible Chat Completions API
  - non-streaming JSON response
  - streaming ผ่าน Server-Sent Events (`stream: true`)
- Embeddings API สำหรับข้อความเดี่ยวหรือ array ของข้อความ
- เก็บ short-term conversation history ด้วย Spring AI Chat Memory และ PostgreSQL
- สกัดและเรียกคืน long-term memory จาก PostgreSQL
- ประกอบ prompt ผ่าน Provider Composition System (PCS)
- ค้นเว็บผ่าน SearXNG พร้อม cache บน Valkey
- เลือกว่าจะค้นเว็บหรือไม่ผ่าน rule/LLM decision mode
- วางแผน query แบบ deterministic สำหรับตัด conversational wrapper และสร้าง core query ภาษาไทย/อังกฤษ
- ส่ง language/category/time-range/safe-search options ไปยัง SearXNG พร้อม ranking และ URL deduplication
- Actuator health และ metrics
- คำสั่ง runtime และ diagnostics ที่จัดการในระดับ application

## เทคโนโลยี

- Java 25
- Spring Boot 4.1.0
- Spring AI 2.0.0
- Maven Wrapper (`./mvnw`)
- Ollama-compatible chat และ embedding model
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
| `VALKEY_URL` | `redis://127.0.0.1:6379` | Valkey/Redis endpoint |
| `MINIKUN_SEARCH_SEARXNG_URL` | `http://127.0.0.1:8888` | SearXNG endpoint |
| `MINIKUN_SEARCH_ENABLED` | `true` | เปิด/ปิด web search |
| `MINIKUN_SEARCH_CACHE_ENABLED` | `true` | เปิด/ปิด search cache |
| `MINIKUN_SEARCH_CACHE_TTL` | `PT5M` | อายุ search cache |
| `MINIKUN_SEARCH_SAFESEARCH` | `true` | ส่ง safe-search option ให้ SearXNG |
| `MINIKUN_SEARCH_QUERY_PLANNING_ENABLED` | `true` | เปิด query planning และ core keyword extraction |
| `MINIKUN_SEARCH_QUERY_PLANNING_MAX_ALTERNATES` | `2` | จำนวน alternate queries สูงสุด |
| `MINIKUN_SEARCH_CACHE_PROVIDER_VERSION` | `v1` | version ของ provider ที่รวมใน cache key |
| `MINIKUN_BROWSER_ENABLED` | `true` | เปิด/ปิดการอ่าน URL ผ่าน minikun-browser-worker |
| `MINIKUN_BROWSER_WORKER_URL` | `http://127.0.0.1:3000` | endpoint ของ browser worker |
| `MINIKUN_BROWSER_WORKER_TOKEN` | ว่าง | Bearer token ที่ตรงกับ `BROWSER_WORKER_TOKEN` ของ worker |
| `MINIKUN_BROWSER_TIMEOUT` | `20s` | timeout ของการ render แต่ละ URL |
| `MINIKUN_BROWSER_MAX_URLS` | `5` | จำนวน URL สูงสุดต่อข้อความ |
| `SPRING_AI_CHAT_MEMORY_MAX_MESSAGES` | `20` | จำนวนข้อความ short-term memory สูงสุด |
| `MINIKUN_MEMORY_RECALL_MAXIMUM_COUNT` | `10` | จำนวน long-term memories ที่เรียกคืนสูงสุด |
| `MINIKUN_MEMORY_RECALL_MAXIMUM_CHARACTERS` | `4000` | ขนาด Knowledge context สูงสุด |
| `MINIKUN_MEMORY_REFLECTION_ENABLED` | `false` | เปิด memory reflection หลังจบ conversation |
| `MINIKUN_DIAGNOSTICS_CONVERSATIONAL_ENABLED` | `false` | เปิด diagnostics แบบ conversational |

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

## Memory และ prompt composition

Short-term history ถูกผูกกับ `ConversationId` และเก็บผ่าน Spring AI JDBC Chat Memory ใน PostgreSQL ส่วน long-term memory ถูกเก็บในตาราง `minikun_memory` ตาม schema ใน [`memory-schema.sql`](src/main/resources/memory-schema.sql)

ใน request chat ระบบจะโหลด history เดิม, เรียกคืน knowledge ที่เกี่ยวข้อง, สร้าง prompt ผ่าน PCS แล้วจึงเรียก chat model หลังตอบสำเร็จจึงบันทึก assistant message กลับเข้า conversation memory

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
./script/deploy-minikun-agent.sh
```

ตัวรัน production อยู่ที่ [`script/minikun-agent.sh`](../script/minikun-agent.sh) โดยจะอ่าน `.env` จาก root workspace และใช้ค่าหลักดังนี้:

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
