# minikun-agent

บริการ AI agent แบบ OpenAI-compatible API ที่พัฒนาด้วย Spring Boot และ Spring AI โดยใช้ Ollama เป็น chat/embedding backend พร้อมระบบจัดการ conversation memory, long-term memory, web search และ runtime diagnostics สำหรับใช้งานภายใน homelab

## ความสามารถหลัก

- อ่านสถานะ CLEANLINE UPS ผ่าน NUT ด้วย `ups.status` และ `/v1/ups/status`
  พร้อม [วิธีติดตั้งบน Mac และย้ายไป Linux](docs/ups-nut.md)

- OpenAI-compatible Chat Completions API
  - non-streaming JSON response
  - streaming ผ่าน Server-Sent Events (`stream: true`)
- Embeddings API สำหรับข้อความเดี่ยวหรือ array ของข้อความ
- Voice Companion แบบ local สำหรับ speech-to-text, text-to-speech และ voice turn ต่อเนื่อง
- Personal Knowledge แบบ local สำหรับ index เอกสาร, hybrid retrieval และ citation ในบทสนทนา
- Self-learning Knowledge Agent สำหรับกวาดข้อมูลตามหัวข้อแบบมีตารางเวลา เปิดอ่านต้นฉบับ ตรวจสอบ claim และนำเฉพาะความรู้ที่ผ่านเกณฑ์มาใช้ตอบแชต
- Autonomous Deep Research แบบ bounded สำหรับวาง subquestions, ค้นซ้ำตาม evidence gap, เปิดแหล่งต้นฉบับ และระบุข้อจำกัด
- Storytelling advisor สำหรับวางแรงขับ อุปสรรค stakes ฉาก จังหวะ มุมมอง และตอนจบ พร้อม `Minikun narrative voice` ที่ทำให้งานเล่าเรื่องเป็นธรรมชาติ มีรายละเอียดรูปธรรม และรักษาน้ำเสียงของมินิคุงโดยอัตโนมัติ
- Story Illustration สำหรับสร้างภาพของจังหวะสำคัญหลังเล่าเรื่องเสร็จ แล้วแนบภาพเข้า response ทั้งแบบ JSON และ streaming โดยไม่เก็บ image bytes ใน conversation history
- สร้าง PowerPoint ภาษาไทย/อังกฤษแบบแก้ข้อความได้ พร้อมธีม Layout กราฟิกและภาพประกอบที่มินิคุงเลือก แล้วแก้ฉบับต่อไปจากบทสนทนาได้
- Adaptive Companion ที่เรียนรู้ภาษา ความยาว รูปแบบ ระดับเทคนิค และโทนการตอบแบบ owner-scoped
- Natural Conversation Advisor ที่ใช้เจตนา บริบทต่อเนื่อง และสัญญาณอารมณ์เพื่อปรับคำตอบโดยไม่เก็บข้อความเพิ่ม
- Conversation Policy Engine ที่แยกการรับฟัง ชวนคิด ตัดสินใจ อธิบาย สร้างงาน และลงมือทำ พร้อม question/initiative/challenge contract ราย turn
- Unified Turn Planner ที่สรุป intent, memory/search/tool, background execution และ expert review เป็นแผนเดียวก่อนเริ่มตอบ ลดการตัดสินใจซ้ำและรักษาความต่อเนื่องข้าม turn
- Ambiguity Resolver สำหรับข้อความสั้นที่ตีความได้หลายทาง โดยใช้ task model แบบมี timeout และ fallback เป็นกฎที่คาดเดาได้
- Graceful Recovery สำหรับ summary, memory, search, reviewer และ tool runtime โดยลดระดับความสามารถอย่างปลอดภัย และไม่ retry คำสั่งที่อาจทำซ้ำ
- Safe Action Guard ไม่ retry tool ที่เปลี่ยน state โดยอัตโนมัติ และใช้ confirmation policy เดิมเป็นด่านอนุมัติ
- Companion Mode แบบ conversation-scoped สำหรับสลับพฤติกรรมระหว่าง `companion`, `work` และ `focus`
- Relationship Thread Memory สำหรับจำเรื่องที่ยังคุยไม่จบ และ consent-based check-in ที่เคารพ quiet hours
- Feedback learning จาก 👍/👎 และเหตุผลแบบ closed category เพื่อปรับความยาว น้ำเสียง จำนวนคำถาม initiative และระดับการทักท้วง
- Quality Loop ที่เชื่อม feedback กลับไปยัง intent/execution/profile ของคำตอบผ่าน response ID เพื่อวัดคุณภาพโดยไม่เก็บ prompt หรือคำตอบใน metric
- Minikun Eval Lab สำหรับรัน baseline/custom Turn Plan suite และดู approval rate กับ shadow routing signals จาก feedback โดยไม่เรียก tool หรือเก็บข้อความเพิ่ม
- Communication Assistant สำหรับ draft, rewrite, reply และ summarize โดยใช้โมเดลหลักแบบ draft-only
- Agent Planner + Execution Loop สำหรับคำสั่งหลายขั้น พร้อม state, retry, confirmation stop และ resume จาก PostgreSQL
- Investment Copilot แบบ owner-scoped สำหรับ policy, transaction ledger, average-cost portfolio,
  decision journal, latest-quote valuation, SEC filings และ Alpaca Paper ที่ต้องยืนยันก่อนส่ง
- เก็บ short-term conversation history ด้วย Spring AI Chat Memory และ PostgreSQL
- สกัด long-term memory จาก PostgreSQL และเรียกคืนเชิงความหมายด้วย embedding พร้อม lexical fallback
- ประกอบ prompt ผ่าน Provider Composition System (PCS)
- ค้นเว็บผ่าน SearXNG พร้อม cache บน Valkey
- ตัดสินใจค้นเว็บและวาง semantic query plan ใน model call เดียว พร้อม bounded rule fallback
- รวมผล search, explicit URL, image intent และ local context เป็น external-context action ก่อนเรียก Browser/Search
- วางแผน primary/alternate query, location และ evidence needs แบบ dynamic โดยมี deterministic fallback
- ตรวจคุณภาพคำแนะนำสถานที่และ retry ได้สูงสุดหนึ่งครั้งเมื่อแหล่งข้อมูลหรือรายละเอียดสำคัญบางเกินไป
- ส่ง language/category/time-range/safe-search options ไปยัง search provider พร้อม multi-query ranking และ URL deduplication
- Actuator health และ metrics
- คำสั่ง runtime และ diagnostics ที่จัดการในระดับ application
- Native function tools: `time.get_current_time`, `weather.get_forecast`, `web.search`, `web.open_url`, `image.generate`, `presentation.create`, `presentation.read_latest`, `presentation.revise`, `calculator.add`, `planner.manage`, `calendar.manage`, `task.manage`, `investment.manage`, `investment.analyze`, `investment.data`, `homelab.guardian`, `computer.local`, `knowledge.personal`, `communication.assist` และ `personal.loop`
- ผลลัพธ์จาก tool จะถูกส่งกลับเข้า prompt ของ MCS/PCS เพื่อให้โมเดลตอบต่อด้วยตัวตน บริบท และน้ำเสียงเดิมของมินิคุง
- เก็บ reminder ใน PostgreSQL และส่ง browser notification ผ่าน Cockpit โดยมี ntfy เป็น fallback
- เชื่อม private iCalendar feed จาก Google, Apple หรือ Outlook เพื่ออ่าน agenda และเตือนก่อนนัด
- มี proactive safety policy สำหรับ quiet hours และ daily briefing ที่รวมอากาศ นัดหมาย งาน และสิ่งค้างเวลา 08:00 (`Asia/Bangkok`)
- Closed-loop Personal Agent สำหรับ weekly review, outcome learning, universal inbox,
  safe automation recipes, incident correlation, explainability และ personal timeline
- Minikun Web แบบ mobile-first และ dark theme สำหรับ background chat, vision, ไฟล์ข้อความ,
  voice input/output, ประวัติหลายบทสนทนา รวมถึง Cockpit, inbox และ Personal Experiment ที่ `/cockpit`
- Background chat แบบ durable ที่ resume งานค้างหลัง restart จาก PostgreSQL และสั่ง retry งานที่ล้มเหลวผ่าน API ได้
- Chat productivity ใน Cockpit: ค้นหา/ปักหมุด/เก็บถาวร/เปลี่ยนชื่อ/ทำสำเนา/ลบ/ส่งออกบทสนทนา,
  edit-to-branch, regenerate/retry, feedback, source cards, per-chat draft และไฟล์แนบต่อเนื่องข้าม turn
- Personal Context Runtime สำหรับ context budget, dynamic max-tokens และ bounded recovery
- ตรวจสอบ ลบรายรายการ และล้าง long-term memory แบบ owner-scoped ผ่าน `/v1/memory`

## ลงมือทำงานและตรวจผล

มินิคุงมี durable action plans สำหรับ Homelab, ไฟล์, task/reminder, fixed development/desktop workflows และ browser click/fill/select พร้อม checkpoint, approvals และสิทธิ์ล่วงหน้าที่จำกัด resource/เวลา/จำนวนครั้ง ดูสถานะและอนุมัติได้ที่ **Cockpit → Agent → ฝากงานให้มินิคุง**

ตั้งคำสั่งจริงของเครื่องผ่าน `MINIKUN_GUARDIAN_ACTIONS` และ `MINIKUN_COMPUTER_WORKFLOWS` ก่อนเปิดการแก้ปัญหาอัตโนมัติ รายละเอียด API, configuration และ recovery อยู่ใน [Agent Action Runtime](docs/agent-action-runtime.md)

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
├── knowledge/           personal documents และ autonomous knowledge acquisition/retrieval
├── personality/         profile, preferences และ adaptive response learning
├── communication/       draft/rewrite/reply/summarize แบบไม่ส่งออกภายนอก
├── investment/          policy, immutable ledger, portfolio analysis และ thesis journal
├── pcs/                 Provider Composition System สำหรับสร้าง prompt
├── research/            ตรวจ research intent, มาตรฐานหลักฐาน และโครงสร้างการเล่าเรื่อง
├── search/              search decision, query processing และ SearXNG
├── commands/            runtime commands
├── context/             Personal Context Runtime และ context snapshots
├── diagnostics/         diagnostics และ metrics
└── runtime/             models, version และ cache information

src/main/resources/
├── application.properties
├── memory-schema.sql
├── personal-knowledge-schema.sql
├── knowledge-acquisition-schema.sql
├── investment-schema.sql
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
| `MINIKUN_MODEL_MANAGEMENT_TOKEN` | ใช้ค่า memory token | token สำหรับดูและสลับ chat model ระหว่าง runtime |
| `MINIKUN_EVAL_MANAGEMENT_TOKEN` | ใช้ค่า memory token | token สำหรับเรียก Eval Lab baseline/custom suite และ quality report |
| `MINIKUN_TURN_PLANNING_AMBIGUITY_ENABLED` | `true` | เปิด task-model resolver เฉพาะ turn สั้นที่มีความกำกวม |
| `MINIKUN_TURN_PLANNING_AMBIGUITY_TIMEOUT` | `PT1S` | เวลาสูงสุดของ ambiguity resolver ก่อนใช้ deterministic fallback |
| `EMBEDDING_MODEL` | `qwen3-embedding:0.6b` | multilingual embedding model |
| `OLLAMA_NUM_CTX` | `16384` | context window ของ Ollama; dynamic budget จองพื้นที่คำตอบตาม profile ก่อนจัดบริบท |
| `OLLAMA_KEEP_ALIVE` | `30m` | เก็บโมเดลหลักไว้ใน memory เพื่อลด cold start; ลดค่านี้ถ้า RAM/VRAM ไม่พอให้ main และ task model อยู่พร้อมกัน |
| `MINIKUN_REASONING_MODEL` | ว่าง | โมเดล Ollama สำรองสำหรับ request ที่ตั้ง `reasoning_effort` หรือเข้า deep-research/tool-loop; ว่าง = ใช้โมเดลหลัก |
| `MINIKUN_REASONING_CONTEXT_SIZE` | `8192` | context size ของ reasoning model สำรอง (ขั้นต่ำ 1024) |
| `MINIKUN_REASONING_MODELS` | ว่าง | comma-separated aliases ของโมเดลที่รองรับ thinking เพิ่มเติมจาก qwen3/deepseek/gpt-oss |
| `NVIDIA_API_KEY` | ว่าง | API key ของ NVIDIA Build สำหรับ Kimi K3; ตั้งใน environment เท่านั้น |
| `MINIKUN_KIMI_MODEL` | `moonshotai/kimi-k3` | โมเดล reasoning บน NVIDIA Build |
| `MINIKUN_KIMI_BASE_URL` | `https://integrate.api.nvidia.com/v1` | NVIDIA OpenAI-compatible endpoint |
| `MINIKUN_KIMI_TIMEOUT` | `PT120S` | timeout ของ reasoning request |
| `MINIKUN_CHATGPT_REASONING_ENABLED` | `true` | เปิด ChatGPT ผ่าน Codex App Server เป็นเพื่อน reasoning หลัก |
| `MINIKUN_CHATGPT_COMMAND` | `codex` | executable ของ Codex CLI ที่ login ด้วย ChatGPT แล้ว |
| `MINIKUN_CHATGPT_MODEL` | `gpt-6-luna` | ใช้ Luna Max; ถ้า 6 Luna ยังไม่เปิดให้บัญชี ใช้ `gpt-5.6-luna` ที่รองรับ Max; ถ้าไม่มี Luna ใช้ค่าเริ่มต้นที่ `model/list` รายงาน |
| `MINIKUN_CHATGPT_TIMEOUT` | `PT120S` | timeout ของ peer review จาก ChatGPT |
| `MINIKUN_MODEL_TASK_OLLAMA_MODEL` | `hf.co/mradermacher/llama3.2-typhoon2-3b-GGUF:Q4_K_M` | task model สำหรับ reflection, preference extraction, planner และ search decision; ใช้ native `/api/chat` |
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
| `MINIKUN_SEARCH_DECISION_MODE` | `llm` | ใช้ task model ตัดสินใจและวาง query plan; fast path ใช้เฉพาะ intent ที่ชัด และ local discovery ผ่าน model |
| `MINIKUN_SEARCH_DECISION_TIMEOUT` | `PT2S` | เวลาสูงสุดของ LLM classifier ก่อน fallback อย่างปลอดภัย |
| `MINIKUN_CHAT_BACKGROUND_TIMEOUT` | `15m` | deadline รวมของ background chat และงานภาพต่อเนื่อง |
| `MINIKUN_CHAT_BACKGROUND_MAX_QUEUED` | `32` | จำนวน background chat ที่รอ worker ได้พร้อมกัน |
| `MINIKUN_CHAT_BACKGROUND_MAX_ACTIVE` | `8` | จำนวน background chat ที่ประมวลผลพร้อมกัน |
| `MINIKUN_PRESENTATION_ENABLED` | `true` | เปิดหรือปิดการสร้างและแก้ไข PowerPoint |
| `MINIKUN_PRESENTATION_OUTPUT_DIRECTORY` | `${user.home}/.minikun/presentations` | ที่เก็บไฟล์ PowerPoint ที่สร้าง |
| `MINIKUN_PRESENTATION_MAX_FILE_BYTES` | `20971520` | ขนาดไฟล์ PowerPoint สูงสุด 20 MiB |
| `MINIKUN_PRESENTATION_RETENTION` | `P30D` | อายุไฟล์ก่อนลบอัตโนมัติ |
| `MINIKUN_PRESENTATION_MANAGEMENT_TOKEN` | ใช้ค่า visual token หรือ memory token ถ้ามี | token สำหรับดาวน์โหลดไฟล์ที่สร้าง |

ตั้ง key ก่อนรันแอป โดยไม่ต้องใส่ใน `application.properties`, source code หรือ commit:

```sh
export NVIDIA_API_KEY='nvapi-ใส่คีย์ของเราแทนตรงนี้'
./mvnw spring-boot:run
```

ถ้าใช้ตัวติดตั้ง launchd ของ homelab ให้เพิ่ม `export NVIDIA_API_KEY='...'` ในไฟล์ส่วนตัว
`/Volumes/minikun/homelab/java/script/minikun-agent.sh` ใกล้กลุ่ม credential แล้วรัน `deploy/deploy-minikun-agent.sh` ใหม่

งานปกติยังใช้ Ollama; เมื่อ classifier หรือ `reasoning_effort` เลือก reasoning ระบบจะส่งบริบทที่ผ่านการกรองไป ChatGPT ผ่าน Codex App Server เป็นความเห็นหลัก แล้วให้ Ollama เขียนคำตอบสุดท้าย
ถ้า ChatGPT ปฏิเสธหรือเรียกไม่สำเร็จ ระบบจะใช้ Kimi K3 เป็นความเห็นสำรอง; สำหรับงานกำกวม/เชิงผู้เชี่ยวชาญอาจเรียก ChatGPT และ Kimi แบบ parallel opinion พร้อมกัน
คำถามความเสี่ยงสูง เช่น สุขภาพ การเงิน กฎหมาย และ credential จะไม่ส่ง peer ภายนอกโดยอัตโนมัติ
ก่อนใช้งานให้ตรวจว่า `codex login status` แสดงว่า login ด้วย ChatGPT แล้ว; ดู protocol ได้ที่ [Codex App Server documentation](https://learn.chatgpt.com/docs/app-server)
มินิคุงลบ Codex thread ที่สร้างสำหรับแต่ละ peer review หลังได้ผลลัพธ์ เพื่อไม่ให้ประวัติการเรียกจากมินิคุงค้างใน Codex
| `MINIKUN_VISUAL_GENERATION_ENABLED` | `true` | เปิดการสร้างภาพจากเรื่องและ Image Studio |
| `MINIKUN_VISUAL_TINYGRAD_BASE_URL` | `http://127.0.0.1:8002` | TinyGrad SDXL service ที่มี `/generate` และ `/health` |
| `MINIKUN_VISUAL_TINYGRAD_TOKEN` | ว่าง | Bearer token หากตั้ง `SDXL_SERVER_TOKEN` ฝั่ง TinyGrad |
| `MINIKUN_VISUAL_TINYGRAD_MODEL` | `mala-anime-mix-nsfw-ponyxl` | ชื่อโมเดลที่แสดงกำกับภาพจาก local provider |
| `MINIKUN_VISUAL_TINYGRAD_WIDTH` / `HEIGHT` | `512` / `768` | ขนาดเริ่มต้นแบบประหยัด VRAM ต้องหาร 64 ลงตัว |
| `MINIKUN_VISUAL_TINYGRAD_STEPS` | `40` | diffusion steps เริ่มต้นสำหรับภาพหลัก 512×768 |
| `MINIKUN_VISUAL_TINYGRAD_GUIDANCE` | `6.0` | CFG guidance เริ่มต้น |
| `MINIKUN_VISUAL_TINYGRAD_SCHEDULER` / `SCHEDULE` | `dpmpp2m` / `karras` | sampler defaults |
| `MINIKUN_VISUAL_TINYGRAD_TIMEOUT` | `PT10M` | timeout สำหรับ SDXL generation รวม first-run compile |
| `MINIKUN_VISUAL_AUTO_ILLUSTRATE_STORIES` | `false` | เปิดสร้างภาพอัตโนมัติสำหรับ creative story; หากปิดอยู่ คำขอสร้างภาพโดยตรงยังใช้งานได้เมื่อระบบเปิด |
| `MINIKUN_VISUAL_GENERATION_OUTPUT_DIRECTORY` | `${user.home}/.minikun/generated-images` | ที่เก็บ image bytes; conversation จะเก็บเฉพาะ local URL |
| `MINIKUN_VISUAL_ASYNC_MAX_QUEUED` | `8` | จำนวนงานภาพประกอบที่รอ TinyGrad ได้พร้อมกัน |
| `MINIKUN_VISUAL_ASYNC_MAX_ACTIVE` | `1` | จำนวนงานภาพประกอบที่ใช้ TinyGrad พร้อมกัน |
| `MINIKUN_WEATHER_ENABLED` | `true` | เปิด/ปิด weather capability |
| `MINIKUN_WEATHER_GEOCODING_URL` | `https://geocoding-api.open-meteo.com` | endpoint สำหรับ resolve สถานที่ |
| `MINIKUN_WEATHER_FORECAST_URL` | `https://api.open-meteo.com` | endpoint สำหรับ forecast |
| `MINIKUN_WEATHER_TIMEOUT` | `10s` | timeout ของ geocoding และ forecast |
| `MINIKUN_TIME_DEFAULT_ZONE` | `Asia/Bangkok` | timezone เริ่มต้นของ `time.get_current_time` |
| `MINIKUN_PLANNER_ENABLED` | `true` | เปิด planner และ reminder scheduler |
| `MINIKUN_PLANNER_POLL_INTERVAL_MS` | `30000` | รอบตรวจ reminder ที่ถึงเวลาแล้ว |
| `MINIKUN_TASK_ENABLED` | `true` | เปิด goal/task store, tool และ follow-up scheduler |
| `MINIKUN_INVESTMENT_ENABLED` | `true` | เปิด investment ledger, policy, thesis และ native tools |
| `MINIKUN_INVESTMENT_DEFAULT_BASE_CURRENCY` | `THB` | สกุลเงินฐานเริ่มต้นของพอร์ตในเฟส single-currency |
| `MINIKUN_INVESTMENT_TWELVE_DATA_API_KEY` | ว่าง | key สำหรับราคาหุ้น/ETF ล่าสุดจาก Twelve Data; ว่าง = รายงาน `setup_required` |
| `MINIKUN_INVESTMENT_FRANKFURTER_URL` | `https://api.frankfurter.dev` | reference FX รายวันจาก Frankfurter/ECB ไม่ต้องใช้ key |
| `MINIKUN_INVESTMENT_SEC_TICKER_URL` | `https://www.sec.gov` | SEC endpoint สำหรับ ticker-to-CIK mapping |
| `MINIKUN_INVESTMENT_SEC_USER_AGENT` | `MinikunAgent/1.0 (contact: minikun@example.com)` | User-Agent ที่ส่งให้ SEC EDGAR; เปลี่ยนเป็น contact จริงเมื่อใช้งาน |
| `MINIKUN_INVESTMENT_ALPACA_KEY_ID` / `MINIKUN_INVESTMENT_ALPACA_SECRET` | ว่าง | credentials สำหรับ Alpaca Paper เท่านั้น; ว่าง = ปิด paper order |
| `MINIKUN_INVESTMENT_ALPACA_PAPER_URL` | `https://paper-api.alpaca.markets` | endpoint ของบัญชีจำลอง Alpaca |
| `MINIKUN_TASK_POLL_INTERVAL_MS` | `30000` | รอบตรวจ task follow-up ที่ถึงเวลาแล้ว |
| `MINIKUN_TASK_MANAGEMENT_TOKEN` | ใช้ค่า memory token ถ้ามี | token สำหรับ Task API ที่ใช้โดย dashboard/automation |
| `MINIKUN_COMPANION_MODE_ENABLED` | `true` | เปิด interaction mode แบบ conversation-scoped |
| `MINIKUN_COMPANION_MODE_MAXIMUM_SESSIONS` | `1000` | จำนวน owner/conversation modes ที่เก็บใน memory สูงสุด |
| `MINIKUN_RELATIONSHIP_TIMEZONE` | `Asia/Bangkok` | timezone สำหรับตีความวันและเวลา check-in จากบทสนทนา |
| `MINIKUN_RELATIONSHIP_CHECK_IN_POLL_INTERVAL_MS` | `60000` | รอบตรวจ conversational check-in ที่ได้รับ consent แล้ว |
| `MINIKUN_RELATIONSHIP_MANAGEMENT_TOKEN` | ใช้ค่า memory token ถ้ามี | token สำหรับจัดการ relationship threads |
| `MINIKUN_AGENT_EXECUTION_ENABLED` | `true` | เปิดแผนและ execution tracking สำหรับคำสั่งหลายขั้น |
| `MINIKUN_AGENT_MAX_PLANNED_STEPS` | `8` | จำนวนขั้นในแผนภายในสูงสุด |
| `MINIKUN_AGENT_MAX_TOOL_STEPS` | `12` | จำนวน tool calls ที่บันทึกได้สูงสุดต่อ run |
| `MINIKUN_AGENT_MAX_TOOL_CONTINUATIONS` | `8` | จำนวนรอบ tool continuation สูงสุดก่อนหยุดอย่างปลอดภัย |
| `MINIKUN_AGENT_MAX_RETRIES` | `1` | จำนวน retry อัตโนมัติสำหรับ `EXECUTION_FAILED` ต่อ tool call |
| `MINIKUN_AGENT_MANAGEMENT_TOKEN` | ใช้ค่า task/memory token ถ้ามี | token สำหรับอ่านและ resume Agent Run API |
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
| `MINIKUN_PROACTIVE_ATTENTION_DAILY_MAXIMUM` | `3` | จำนวน briefing/goal review/conversation check-in ที่รบกวนได้ต่อวัน; priority สูงยังผ่านได้ |
| `MINIKUN_PROACTIVE_BRIEFING_ENABLED` | `true` | เปิด daily briefing ที่รวมอากาศ นัดหมาย งาน และสิ่งค้าง |
| `MINIKUN_PROACTIVE_BRIEFING_OWNER_ID` | `default` | owner ที่ใช้สร้าง daily briefing ใน deployment แบบ single-user |
| `MINIKUN_PROACTIVE_BRIEFING_TIME` | `08:00` | เวลาท้องถิ่นที่ส่ง daily briefing |
| `MINIKUN_PROACTIVE_BRIEFING_WEATHER_LOCATION` | `Bangkok` | สถานที่สำหรับสรุปอากาศใน daily briefing |
| `MINIKUN_PROACTIVE_BRIEFING_WEATHER_COUNTRY_CODE` | `TH` | country code สำหรับสรุปอากาศใน daily briefing |
| `MINIKUN_NTFY_ENABLED` | `true` | เปิด/ปิดการส่ง ntfy |
| `MINIKUN_NTFY_TOKEN` | ว่าง | Bearer token สำหรับ ntfy topic ถ้าตั้ง access control |
| `MINIKUN_NTFY_REMINDER_TOPIC` | topic ที่กำหนดใน `application.properties` | topic สำหรับ reminder |
| `MINIKUN_NOTIFICATION_BROWSER_ENABLED` | `true` | ส่ง notification เข้า Cockpit ผ่าน browser SSE |
| `MINIKUN_NTFY_FALLBACK_ENABLED` | `true` | ใช้ ntfy เมื่อไม่มี browser ที่เชื่อมต่ออยู่ |
| `MINIKUN_NOTIFICATION_MANAGEMENT_TOKEN` | ใช้ค่า task/memory token ถ้ามี | token สำหรับอ่านประวัติการส่ง notification |
| `MINIKUN_NOTIFICATION_SCHEDULER_STALE_AFTER` | `2m` | ระยะที่ scheduler ไม่ poll ก่อน health เปลี่ยนเป็น `DOWN` |
| `MINIKUN_NOTIFICATION_SCHEDULER_FAILURE_THRESHOLD` | `3` | จำนวน delivery failure ติดต่อกันก่อน health เปลี่ยนเป็น `DOWN` |
| `MINIKUN_SYNC_ENABLED` | `true` | เปิด server-side chat sync และ device pairing สำหรับ Cockpit |
| `MINIKUN_SYNC_OWNER_ID` | `default` | owner ภายในของชุดอุปกรณ์แบบ single-user |
| `MINIKUN_SYNC_CANONICAL_ORIGIN` | `https://mini-kun:8443` | origin สำหรับ pairing link ของเครื่องลูกที่เชื่อมผ่าน Tailscale |
| `MINIKUN_SYNC_PAIRING_TTL` | `PT2M` | อายุรหัสจับคู่อุปกรณ์แบบใช้ครั้งเดียว |
| `MINIKUN_SYNC_SESSION_TTL` | `P180D` | อายุ session ของอุปกรณ์ที่จับคู่แล้ว |

Voice Companion กำหนดค่าผ่าน `minikun.voice.*` ใน `application.properties` โดยค่าเริ่มต้นใช้
Whisper Large V3 Turbo Q4 ผ่าน MLX สำหรับถอดเสียง และ TTS แบบ local บน external drive:
`VaniraTTS` สำหรับภาษาไทย/คำอังกฤษสั้น ๆ และ `Kokoro-82M` สำหรับวลีภาษาอังกฤษต่อเนื่อง
(ค่าเริ่มต้นใช้เสียงผู้ชาย Vanira speaker 3 และ Kokoro `am_michael`)
ไฟล์เสียงถูกจำกัดขนาด 10 MB และมีเฉพาะใน memory/temporary file ระหว่าง request เท่านั้น

TTS โหลดเมื่อมีคำขอเสียงครั้งแรก (`MINIKUN_VOICE_TTS_WARMUP=false` ใน launchd) แล้วเก็บโมเดลไว้
สั่งแชต `ปิด TTS` เพื่อรอเสียงที่กำลังสร้างให้จบ หยุด Python process ที่มินิคุงดูแล และคืน RAM;
คำขอเสียงถัดไปจะไม่เปิด process กลับจนกว่าจะสั่ง `เปิด TTS` การถอดเสียง Whisper ยังใช้งานได้
สั่ง `สถานะ TTS` เพื่อตรวจสถานะ หรือใช้ `GET /v1/audio/tts` และ
`POST /v1/audio/tts` พร้อม JSON `{"enabled":false}` / `{"enabled":true}`
ถ้าตั้ง model management token ให้ส่ง header `X-Minikun-Model-Token` เช่นเดียวกับ `/v1/models/runtime`
สถานะเปิด/ปิดมีผลจนรีสตาร์ตมินิคุง; ถ้าเป็น TTS server ที่เปิดจากภายนอก ต้องหยุดผ่านเจ้าของ process นั้น
เสียงอ่านจากเบราว์เซอร์หรือ macOS เป็นคนละส่วนกับ Python TTS runtime

| `MINIKUN_SEARCH_CACHE_ENABLED` | `true` | เปิด/ปิด search cache |
| `MINIKUN_SEARCH_CACHE_TTL` | `PT5M` | อายุ search cache |
| `MINIKUN_SEARCH_SAFESEARCH` | `true` | ส่ง safe-search option ให้ SearXNG |
| `MINIKUN_SEARCH_RESULT_LIMIT` | `8` | จำนวนผลลัพธ์ search ต่อ query |
| `MINIKUN_SEARCH_QUERY_PLANNING_ENABLED` | `true` | เปิด query planning และ core keyword extraction |
| `MINIKUN_SEARCH_QUERY_PLANNING_MAX_ALTERNATES` | `2` | จำนวน alternate queries สูงสุด |
| `MINIKUN_SEARCH_CACHE_PROVIDER_VERSION` | `v3` | version ของ provider ที่รวมใน cache key |
| `MINIKUN_SEARCH_PARALLEL_QUERIES_ENABLED` | `true` | ทำ expanded search queries แบบ parallel |
| `MINIKUN_SEARCH_PARALLEL_QUERIES_MAX_CONCURRENCY` | `3` | จำนวน search query สูงสุดที่ทำพร้อมกัน |
| `MINIKUN_SEARCH_DECISION_TIMEOUT` | `PT2S` | timeout เฉพาะ search classifier ก่อนใช้ rule fallback |
| `MINIKUN_BROWSER_ENABLED` | `true` | เปิด/ปิดการอ่าน URL ผ่าน Crawl4AI |
| `MINIKUN_CRAWL4AI_BASE_URL` | `http://127.0.0.1:11235` | endpoint ของ Crawl4AI |
| `MINIKUN_CRAWL4AI_TOKEN` | ใช้ค่า `CRAWL4AI_API_TOKEN` | Bearer token สำหรับ Crawl4AI |
| `MINIKUN_BROWSER_TIMEOUT` | `25s` | HTTP timeout ต่อการ render หนึ่งครั้ง |
| `MINIKUN_BROWSER_MAX_URLS` | `5` | จำนวน URL สูงสุดต่อข้อความ |
| `MINIKUN_BROWSER_BLOCK_PRIVATE_ADDRESSES` | `true` | ป้องกัน Crawl4AI เข้าถึง localhost/private network |
| `MINIKUN_BROWSER_MAX_CONTENT_CHARACTERS` | `12000` | ขนาดเนื้อหาสูงสุดต่อ URL ก่อนใส่เข้า Knowledge context |
| `MINIKUN_BROWSER_MAX_CONCURRENCY` | `3` | จำนวน URL ที่ Crawl4AI อ่านพร้อมกัน |
| `MINIKUN_BROWSER_PAGE_TIMEOUT` | `15s` | timeout โหลดหน้าเว็บ (สูงสุด 60s) |
| `MINIKUN_BROWSER_SETTLE_DELAY` | `1500ms` | รอ JavaScript ก่อนเก็บเนื้อหา (สูงสุด 5s) |
| `MINIKUN_BROWSER_RETRY_DELAY` | `1s` | พักก่อน retry; รองรับ HTTP 429/5xx และ Retry-After โดยพักสูงสุด 10s |
| `MINIKUN_BROWSER_CACHE_TTL` | `5m` | อายุ cache เนื้อหาเว็บที่อ่านสำเร็จ (`0s` ปิด cache) |
| `MINIKUN_BROWSER_CACHE_CAPACITY` | `128` | จำนวนหน้าใน cache ภายใน process |
| `MINIKUN_BROWSER_DOMAIN_INTERVAL` | `2s` | เว้นช่วงระหว่างการอ่านโดเมนเดียวกัน รวมคำขอจากหลายแชต |
| `MINIKUN_BROWSER_RUNTIME_ROOT` | `~/Library/Application Support/Minikun/browser` | runtime และ profile สำหรับ browser บน Mac |
| `MINIKUN_BROWSER_SESSION_TIMEOUT` | `35s` | timeout ต่อคำสั่งเปิด/อ่าน browser session (สูงสุด 60s) |
| `MINIKUN_RESEARCH_SOURCE_READ_LIMIT` | `3` | จำนวนแหล่งต้นฉบับจากผลค้นหาที่เปิดอ่านใน deep-research path (`0` เพื่อปิด stage นี้) |
| `MINIKUN_RESEARCH_AUTONOMOUS_ENABLED` | `true` | เปิด plan-search-read-evaluate loop สำหรับ explicit deep research |
| `MINIKUN_RESEARCH_AUTONOMOUS_MAX_ITERATIONS` | `3` | จำนวนรอบประเมินและค้นซ้ำสูงสุด (`1-5`) |
| `MINIKUN_RESEARCH_AUTONOMOUS_MAX_QUERIES` | `8` | จำนวน query รวมสูงสุดต่อ research turn (`1-20`) |
| `MINIKUN_RESEARCH_AUTONOMOUS_MAX_FOLLOW_UP_QUERIES` | `3` | จำนวน gap queries ที่ evaluator สร้างได้ต่อรอบ (`1-4`) |
| `MINIKUN_RESEARCH_AUTONOMOUS_EVALUATION_MAX_CHARACTERS` | `12000` | evidence budget สำหรับ coverage evaluator |
| `MINIKUN_RESEARCH_AUTONOMOUS_TIMEOUT` | `PT60S` | deadline รวมของ autonomous research loop |
| `MINIKUN_KNOWLEDGE_ACQUISITION_ENABLED` | `true` | เปิด topic API, acquisition agent และ verified retrieval |
| `MINIKUN_KNOWLEDGE_ACQUISITION_SCHEDULER_ENABLED` | `true` | เปิดการกวาดหัวข้อที่ถึงกำหนดอัตโนมัติ |
| `MINIKUN_KNOWLEDGE_ACQUISITION_POLL_INTERVAL_MS` | `300000` | ช่วงเวลาที่ scheduler ตรวจหัวข้อที่ถึงกำหนด |
| `MINIKUN_KNOWLEDGE_ACQUISITION_TOPICS_PER_RUN` | `1` | จำนวนหัวข้อสูงสุดต่อ scheduler batch |
| `MINIKUN_KNOWLEDGE_ACQUISITION_TIMEOUT` | `PT60S` | deadline ของ research run ต่อหัวข้อ |
| `MINIKUN_KNOWLEDGE_ACQUISITION_SOURCE_READ_LIMIT` | `5` | จำนวนแหล่งต้นฉบับสูงสุดที่เปิดอ่านต่อ run |
| `MINIKUN_KNOWLEDGE_ACQUISITION_MINIMUM_CONFIDENCE` | `0.7` | confidence ขั้นต่ำก่อนเข้า publication gate |
| `MINIKUN_KNOWLEDGE_ACQUISITION_TOKEN` | ใช้ knowledge/memory token ถ้ามี | token สำหรับ API จัดการหัวข้อ run และ claim |
| `SPRING_AI_CHAT_MEMORY_MAX_MESSAGES` | `20` | จำนวนข้อความ short-term memory สูงสุด |
| `MINIKUN_MEMORY_RECALL_MAXIMUM_COUNT` | `10` | จำนวน long-term memories ที่เรียกคืนสูงสุด |
| `MINIKUN_MEMORY_RECALL_MAXIMUM_CHARACTERS` | `4000` | ขนาด Knowledge context สูงสุด |
| `MINIKUN_MEMORY_REFLECTION_ENABLED` | `true` | เปิด memory reflection หลังจบ conversation |
| `MINIKUN_MEMORY_SEMANTIC_ENABLED` | `true` | ใช้ embedding จัดอันดับ long-term memory ตามความหมาย |
| `MINIKUN_MEMORY_SEMANTIC_WEIGHT` | `0.85` | น้ำหนัก semantic similarity เทียบกับ confidence |
| `MINIKUN_DIAGNOSTICS_CONVERSATIONAL_ENABLED` | `false` | เปิด diagnostics แบบ conversational |
| `MINIKUN_CONTEXT_BUDGET_CHARACTERS` | `24000` | character budget สำหรับ prompt context |
| `MINIKUN_CONTEXT_BUDGET_CREATIVE_CHARACTERS` | `40000` | character budget ที่ใช้กับการแต่งเรื่องและเล่าเรื่อง เพื่อรักษาเนื้อหาตอนก่อนหน้าได้มากขึ้น |
| `MINIKUN_TOKEN_BUDGET_RESERVED_OUTPUT_TOKENS` | `256` | output reserve ก่อนคำนวณ dynamic max-tokens |
| `MINIKUN_MODEL_GENERATION_MAX_TOKENS` | `4096` | เพดาน output รวมของแอป |
| `MINIKUN_GENERATION_CREATIVE_MAX_TOKENS` | `4096` | เพดาน output สำหรับการแต่งเรื่อง เล่าเรื่อง และนิทาน |
| `MINIKUN_GENERATION_SEARCH_MAX_TOKENS` | `3072` | เพดาน output สำหรับคำตอบที่ใช้ผลค้นเว็บหรือ Browser |
| `MINIKUN_GENERATION_RESEARCH_MAX_TOKENS` | `4096` | เพดาน output สำหรับ deep research และการสังเคราะห์หลายแหล่ง |
| `MINIKUN_CONTINUATION_ENABLED` | `true` | ต่อคำตอบทุกโปรไฟล์อัตโนมัติหนึ่งรอบเมื่อโมเดลจบด้วย `finish_reason=length` (รองรับชื่อตัวแปรเดิม `MINIKUN_CREATIVE_CONTINUATION_ENABLED`) |
| `MINIKUN_CONTINUATION_TAIL_CHARACTERS` | `12000` | ปลายข้อความเดิมที่ใช้สร้างรอยต่ออย่างต่อเนื่อง (รองรับชื่อตัวแปรเดิม) |
| `MINIKUN_CONTINUATION_MAX_TOKENS` | `1024` | งบสำหรับปิดคำตอบอย่างสมบูรณ์หลังชนเพดาน (รองรับชื่อตัวแปรเดิม) |
| `MINIKUN_MEMORY_MANAGEMENT_TOKEN` | ว่าง | token สำหรับป้องกัน API จัดการ memory |
| `MINIKUN_ADAPTATION_MANAGEMENT_TOKEN` | ใช้ค่า memory token ถ้ามี | token สำหรับดู ให้ feedback และ reset Adaptive Companion |
| `MINIKUN_COMMUNICATION_MANAGEMENT_TOKEN` | ใช้ค่า memory token ถ้ามี | token สำหรับ Communication Assistant API |

Investment และ Crawl4AI credentials ให้กำหนดเป็น `export` ใน launcher ส่วนตัว
`/Volumes/minikun/homelab/java/script/minikun-agent.sh` แล้วรัน `./deploy/deploy-minikun-agent.sh`;
deploy จะไม่อ่านค่าเหล่านี้จาก `.env` และจะฝังค่าไว้ใน launcher ที่ติดตั้งสำหรับ LaunchAgent

ตัวอย่างชื่อค่าที่อยู่ใน launcher:

```sh
export CRAWL4AI_API_TOKEN="..."
export MINIKUN_INVESTMENT_TWELVE_DATA_API_KEY="..."
export MINIKUN_INVESTMENT_ALPACA_KEY_ID="..."
export MINIKUN_INVESTMENT_ALPACA_SECRET="..."
export MINIKUN_INVESTMENT_SEC_USER_AGENT="MinikunAgent/1.0 (contact: your-email@example.com)"
```

Browser จะอ่านหลาย URL แบบ best-effort: URL ที่อ่านไม่ได้จะถูกบันทึกเป็น failure แต่ URL อื่นยังถูกส่งต่อให้ model ได้ ส่วน URL ที่ชี้ไปยัง localhost หรือ private address จะถูก block โดยค่าเริ่มต้นเพื่อป้องกัน SSRF; หากต้องการเปิด resource ภายในอย่างตั้งใจควรทำ allowlist แยกที่ browser worker/gateway แทนการปิด policy ทั้งหมด

External context planner จะเลือก action ระหว่าง `MEMORY_ONLY`, `OPEN_EXPLICIT_URL`, `SEARCH_WEB`, `SEARCH_THEN_OPEN` และ `IMAGE_SEARCH` ก่อนเรียก external runtime โดย browser content ที่เป็นหน้า error หรือ login/access-blocked จะถูกคัดออกจาก Knowledge context

Search และ Browser มี source-quality gate แบบ conservative สำหรับตรวจหน้า error, access-blocked และข้อความที่มีลักษณะ prompt injection; เนื้อหาที่สั้นหรือข้อมูลน้อยจะถูกติดป้ายคุณภาพต่ำแต่ยังคงไว้ เพื่อไม่ให้ snippet ที่ถูกต้องแต่สั้นถูกทิ้งโดยอัตโนมัติ

### Deep Research และ Storytelling

ข้อความที่ระบุเจตนาอย่าง `ค้นคว้า`, `วิจัย`, `เจาะลึก`, `research`, `fact-check` หรือ
`deep dive` จะเข้า autonomous research loop แบบ bounded:

1. task model แยก objective เป็น subquestions และ standalone search queries
2. Search executor ค้น query ชุดแรกแบบ parallel และเก็บ URL provenance ใน evidence ledger
3. Browser executor เปิดแหล่งต้นฉบับตาม source budget
4. task model ประเมิน coverage จากหลักฐานที่ได้ โดยไม่สร้างคำตอบสุดท้าย
5. หากยังมีช่องว่าง evaluator จะสร้าง follow-up queries แล้ววนกลับไปค้นรอบถัดไป
6. loop หยุดเมื่อหลักฐานเพียงพอ, ไม่มี query ใหม่, ถึงเพดานรอบ/query หรือหมด deadline

final model จะได้รับเฉพาะ evidence, execution trace และ unresolved gaps สำหรับสังเคราะห์คำตอบ ไม่ได้รับ
hidden reasoning ของ planner/evaluator หาก task model วางแผนหรือประเมินไม่ได้ ระบบจะเก็บหลักฐานจาก
deterministic seed query และส่งต่อแบบ fail-open แทนการทำให้ทั้งคำขอล้มเหลว หาก browser worker อ่านบางหน้าไม่ได้
ระบบจะใช้แหล่งที่เหลือและ search snippets ต่อ

เมื่อมี Search, Browser หรือ Personal Knowledge ใน context โมเดลจะได้รับ evidence contract ที่กำหนดให้:

- แยกข้อเท็จจริง การอนุมาน และความไม่แน่ใจ
- วาง citation ติดกับข้อกล่าวอ้างที่หลักฐานรองรับ โดยใช้ URL หรือ `knowledge://` ที่มีอยู่จริงเท่านั้น
- แสดงข้อมูลที่ขัดแย้งพร้อมอ้างทั้งสองฝ่าย และระบุเมื่อหลักฐานไม่พอ
- ไม่สร้าง citation, คำพูด, เหตุการณ์, แรงจูงใจ หรือความสัมพันธ์เชิงเหตุผลที่ไม่มีในหลักฐาน

คำขอให้เล่า อธิบาย เปรียบเทียบ วิเคราะห์ หรือเรียงประวัติจะเลือกโครงสร้างตอบแบบ dynamic ได้แก่
`STORY`, `EXPLANATION`, `COMPARISON`, `ANALYSIS` และ `TIMELINE` โดยคงภาษา น้ำเสียง และตัวตนจาก MCS
เหมือนเดิม คำถามทั่วไปที่ไม่มี intent เหล่านี้จะไม่เพิ่ม prompt overhead และยังใช้ fast path เดิม

เมื่อเปิด `MINIKUN_VISUAL_GENERATION_ENABLED=true` ระบบจะใช้ TinyGrad SDXL service ที่
`http://127.0.0.1:8002` เป็นค่าเริ่มต้น และสร้างภาพหนึ่งภาพหลังข้อความพร้อมแล้วสำหรับ
คำขอสร้างภาพโดยตรง, เรื่องที่ขอภาพประกอบอย่างชัดเจน และ creative story (ถ้าเปิด auto-illustrate)
ภาพจะถูกเก็บเป็นไฟล์ local แบบตรวจ magic bytes และจำกัดขนาด แล้วส่งกลับเป็น attachment ที่มี
`origin=generated`; หาก image provider ล้มเหลว คำตอบข้อความยังสำเร็จตามปกติ ตัวอย่างค่าขั้นต่ำ:

```sh
export MINIKUN_VISUAL_GENERATION_ENABLED=true
```

คำขออินโฟกราฟิกและการ์ดข้อความในแชตให้ Gemma 4 เขียนเนื้อหาเป็น JSON แล้วแอปจัด SVG ลงแม่แบบหัวเรื่อง การ์ด 1–3 ใบ และสรุป โดยวัดความกว้างข้อความและตัดบรรทัดภาษาไทยภายในกล่อง หากเนื้อหายาวเกินพื้นที่จะให้โมเดลย่อแล้วลองใหม่หนึ่งครั้ง ผังงาน ไทม์ไลน์ และกราฟยังใช้ JSON ขององค์ประกอบกราฟิก โดยข้อความจะตัดบรรทัดตามกล่องที่ครอบอยู่ SVG เปิดดูและดาวน์โหลดได้ ส่วนภาพวาดทั่วไปให้ Gemma 4 เตรียม Pony prompt แล้วใช้ TinyGrad สร้าง PNG

TinyGrad provider เรียก `POST /generate`, ส่ง prompt, negative prompt, face prompts, ขนาด, steps,
guidance, scheduler, schedule และ seed แล้วรับ `image/png` โดยตรง ระบบสร้างภาพรองรับเฉพาะ local
TinyGrad `sdxl_use.py --serve 8002` และไม่มี OpenAI image-generation fallback

ติดตั้งหรือ restart TinyGrad SDXL เป็น LaunchAgent ที่เปิดพร้อม login และ restart อัตโนมัติ:

```sh
./deploy/deploy-minikun-sdxl.sh
```

runtime production ใช้ checkpoint และ LoRA ชุด local เดิม แต่ปิด ADetailer เป็นค่าเริ่มต้นเพื่อไม่ให้
เกิน VRAM ระหว่าง conditioning; เปิดกลับได้ด้วย `MINIKUN_SDXL_ADETAILER_ENABLED=1` เมื่อมี VRAM เพียงพอ

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

รองรับฟิลด์หลัก `model`, `messages`, `conversation_id`, `stream`, `temperature`, `max_tokens`, `max_completion_tokens` และ `reasoning_effort` (`off`, `auto`, `low`, `medium`, `high`)

การตรวจคำตอบสั้น: log `process=model_generation event=outcome` แสดง `requested_max_tokens`,
`prompt_tokens`, `completion_tokens`, `finish_reason` และจำนวน stop sequences โดยไม่บันทึกเนื้อหาแชต
ค่า max tokens เป็นเพดาน ไม่ใช่ความยาวขั้นต่ำ: `stop` หมายถึงจบตามสัญญาณหยุดของโมเดลหรือ stop sequence,
ส่วน `length` หมายถึงชนข้อจำกัดความยาว อย่าสรุปว่าชน dynamic budget จากจำนวน token คำตอบเพียงอย่างเดียว

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

เมื่อข้อความล่าสุดมี HTTP/HTTPS URL ระบบจะเรียก Crawl4AI `/crawl` เพื่อรับทั้ง Fit และ Raw Markdown
ในคำขอเดียว รอ `body` และ JavaScript ตามเวลาที่กำหนด พร้อมเลื่อนหน้าไม่เกิน 8 ขั้น
หาก Fit ว่างหรือสั้นกว่า 200 ตัวอักษรจะใช้ Raw ส่วนก่อนส่งเข้าโมเดลจะเลือกข้อความจาก Raw
ที่ตรงคำถาม (รองรับไทย/อังกฤษ) แล้วคุมขนาดตาม `MINIKUN_BROWSER_MAX_CONTENT_CHARACTERS`
ถ้าไม่มีคำสำคัญจะเก็บตัวอย่างจากต้น กลาง และท้ายหน้า โดยระบุ `Truncated: true` เมื่อเลือกเพียงบางส่วน

ลิงก์แบบ `[ชื่อ](https://example.com/article)` รองรับโดยไม่ส่งวงเล็บปิดส่วนเกินไปยัง crawler
ตรวจหน้า Cloudflare/CAPTCHA จาก HTML และข้อความ แล้วส่งสถานะอ่านไม่สำเร็จให้โมเดลแจ้งผู้ใช้
แทนการสรุปหน้า challenge เป็นบทความ ระบบไม่ retry challenge หรือ 401/403 โดยอัตโนมัติ
เนื้อหาที่อ่านสำเร็จมี cache แบบ TTL และจำกัดขนาด ส่วนการอ่านใหม่คุม concurrency ร่วมทุกแชต
พร้อมเว้นช่วงต่อโดเมนและพักก่อน retry network/429/5xx ไม่มีการหมุน IP หรือแก้ CAPTCHA อัตโนมัติ

ตัวอย่างการส่งลิงก์ให้สรุป:

```sh
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [{"role": "user", "content": "สรุปบทความนี้ https://example.com/article"}],
    "stream": false
  }'
```

ตั้ง `MINIKUN_CRAWL4AI_BASE_URL` ให้ agent มองเห็น Crawl4AI และกำหนด
`CRAWL4AI_API_TOKEN` เป็น `export` ใน `/Volumes/minikun/homelab/java/script/minikun-agent.sh`.

#### ยืนยันหรือล็อกอินผ่าน browser session

ติดตั้ง runtime บน Mac ที่รันมินิคุงครั้งเดียวด้วย `./deploy/install-browser-runtime.sh`
(Playwright + Chromium อยู่ใน venv แยก ไม่มี Java dependency ใหม่) แล้ว deploy agent ตามขั้นตอนเดิม
การ deploy รอบถัดไปจะอัปเดต `session.py` หากติดตั้ง runtime นี้ไว้แล้ว

1. เปิดหน้าตั้งค่า → **Browser session** จากอุปกรณ์ที่จับคู่แล้ว
2. วาง URL แล้วกด **เปิดบน Mac**: หน้าต่าง Chromium จะเปิดบน Mac ที่รัน agent
3. ยืนยัน CAPTCHA หรือล็อกอินด้วยตัวเอง แล้วกด **อ่านต่อหลังยืนยัน**
4. ส่ง URL เดิมในแชต มินิคุงจะอ่านจาก browser session ที่เปิดไว้สำหรับโดเมนนั้น
5. กด **ปิด browser** เพื่อหยุดใช้ session และล้าง cache; cookies ยังอยู่ใน profile แยกเพื่อใช้ครั้งถัดไป

browser ใช้ private stdio ไม่มี browser-control HTTP/CDP port และปิด downloads/service workers/WebSockets
การเชื่อมต่อผ่าน proxy ชั่วคราวบน loopback ที่ตรวจและ pin public IP เพื่อบล็อก private/local network รวม DNS rebinding ใช้ profile เดียวสำหรับเจ้าของมินิคุงและอุปกรณ์ที่จับคู่กับเจ้าของนั้นเท่านั้น
API `/v1/browser/session` (GET/DELETE), `/open` และ `/read` (POST) ต้องมี paired-device cookie และเป็น same-origin
จึงไม่ให้หน้าเว็บภายนอกสั่งเปิดหรืออ่าน browser ได้

การตรวจ challenge และการเลือกข้อความใช้ heuristic จึงไม่รับประกันทุกเว็บ การรอหน้าเป็นเวลาจำกัด
ไม่รองรับ infinite scroll ทั้งหน้าโดยไม่สิ้นสุด การยืนยันทำบน Mac ไม่ได้สตรีมหน้าจอไปยังโทรศัพท์
profile และไฟล์ cookies ส่วนตัว (`session-state.json`) สำหรับล้างข้อมูลล็อกอินทั้งหมดอยู่ใน `<runtime-root>/profile`
ให้ปิด browser ก่อนลบโฟลเดอร์นี้ ทดสอบ offline ด้วย `python3 browser/test_session.py`
และทดสอบ browser จริงพร้อมการเก็บ cookies ข้ามการเปิดใหม่ด้วย `python3 browser/smoke_session.py` (ต้องมีอินเทอร์เน็ต)

ระบบจะส่ง `X-Conversation-Id` กลับมาใน response หาก request ไม่ได้ระบุ conversation ID ระบบจะสร้าง UUID ใหม่ให้โดยอัตโนมัติ ลำดับการเลือก ID คือ:

1. `X-Conversation-Id`
2. `conversation_id` ใน JSON body
3. `X-OpenWebUI-Chat-Id`, `X-Chat-Id` หรือ `Chat-Id`
4. UUID ที่สร้างใหม่

### Models

```sh
curl http://127.0.0.1:8080/v1/models
```

ดูโมเดลที่ติดตั้งใน Ollama และสลับโมเดลหลักได้ทันทีโดยไม่ restart server (ค่าจะกลับเป็นค่า config เมื่อ restart):

```sh
curl http://127.0.0.1:8080/v1/models/runtime
curl -X PUT http://127.0.0.1:8080/v1/models/runtime \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen3:8b"}'
```

### Agent Planner + Execution Loop

เมื่อข้อความมีลำดับชัดเจน เช่น `จากนั้น`, `แล้วค่อย`, `ทำตามแผน` หรือมี action อย่างน้อย
3 รายการ ระบบจะสร้าง bounded plan ภายในและบันทึก run/แต่ละ tool call ลง PostgreSQL โดยมีสถานะ
`PLANNED`, `RUNNING`, `WAITING_CONFIRMATION`, `COMPLETED`, `COMPLETED_WITH_ERRORS`,
`FAILED` และ `LIMIT_REACHED` คำสั่งเขียนที่ต้องยืนยันจะหยุดทันทีและไม่ถูก retry หรือ resume
โดยข้าม confirmation policy ส่วน tool ที่ล้มเหลวด้วย `EXECUTION_FAILED` จะ retry ตามจำนวนที่กำหนด

ทุก plan จะถูกประเมินความเสี่ยงแบบ deterministic เป็น `LOW`, `MEDIUM`, `HIGH` หรือ `CRITICAL`
และบันทึก `risk_level` กับเหตุผลไว้ใน agent run ด้วย งานที่มีผลกระทบภายนอก งานลบข้อมูล
งานการเงิน หรือการเปลี่ยนแปลงระบบจะถูกกำหนดให้ต้องผ่าน explicit review/confirmation เสมอ
การประเมินนี้เป็น safety gate เพิ่มเติม ไม่ได้แทน confirmation policy ของแต่ละ tool

ตรวจรายการและรายละเอียดแต่ละ run:

```sh
curl -H "X-Minikun-Agent-Token: $MINIKUN_AGENT_MANAGEMENT_TOKEN" \
  'http://127.0.0.1:8080/v1/agent/runs?owner_id=default&limit=20'
curl -H "X-Minikun-Agent-Token: $MINIKUN_AGENT_MANAGEMENT_TOKEN" \
  'http://127.0.0.1:8080/v1/agent/runs/<run-id>?owner_id=default'
```

run ที่จบแล้วแต่มี failed tool step สามารถ replay จาก arguments ที่บันทึกไว้ได้ การ replay
ยังใช้ tool policy เดิมทุกอย่างและ owner ต้องตรงกัน:

```sh
curl -X POST -H "X-Minikun-Agent-Token: $MINIKUN_AGENT_MANAGEMENT_TOKEN" \
  'http://127.0.0.1:8080/v1/agent/runs/<run-id>/resume?owner_id=default'
```

### Companion Mode

### Goals และ Daily Care

มินิคุงมี goal state แยกจาก task เพื่อดูแลเป้าหมายระยะยาวแบบ owner-scoped โดยเก็บสถานะ,
progress, metric, current/target value และเวลาทบทวนถัดไป เป้าหมายที่เปิดอยู่จะถูกรวมใน
daily briefing พร้อมงานและนัดหมาย

ตัวอย่าง API:

```sh
curl 'http://127.0.0.1:8080/v1/goals?owner_id=default'
curl -X POST -H 'Content-Type: application/json' \
  -d '{"conversationId":"home","title":"อ่านหนังสือ","metric":"บท","targetValue":12,"progressPercent":0,"timezone":"Asia/Bangkok"}' \
  'http://127.0.0.1:8080/v1/goals?owner_id=default'
curl -X PUT -H 'Content-Type: application/json' \
  -d '{"progressPercent":50,"currentValue":6}' \
  'http://127.0.0.1:8080/v1/goals/<goal-id>/progress?owner_id=default'
```

Task สามารถผูกกับ goal ได้ด้วย `goal_id` (แยกจาก `parent_id`) ทั้งผ่าน `/v1/tasks`
และ tool `task.manage` เมื่อ task ที่ผูกไว้เสร็จ มินิคุงจะ reconcile progress ของ goal
ใน daily briefing โดยคำนวณจากจำนวน linked tasks ที่เสร็จแล้ว

การเปลี่ยนแปลง goal ใช้ `X-Minikun-Goal-Token` เมื่อกำหนด
`MINIKUN_GOAL_MANAGEMENT_TOKEN` และ progress 100% จะปิด goal อัตโนมัติ

เมื่อถึง `next_review_at` ระบบจะส่ง goal review notification แบบ idempotent
ผ่าน `GoalReviewScheduler` และไม่ส่งซ้ำสำหรับรอบ review เดิม สามารถดูภาพรวมส่วนตัวได้ที่:

```sh
curl -H "X-Minikun-Personal-Token: $MINIKUN_PERSONAL_STATUS_TOKEN" \
  'http://127.0.0.1:8080/v1/personal/status?owner_id=default'
```

snapshot นี้รวมจำนวน task ที่เปิดอยู่/เกินกำหนด, goal ที่ต้องทบทวน และ agent run ที่กำลังรอ confirmation

ดู next action ที่เล็กที่สุดของแต่ละเป้าหมายได้ที่:

```sh
curl -H "X-Minikun-Personal-Token: $MINIKUN_PERSONAL_STATUS_TOKEN" \
  'http://127.0.0.1:8080/v1/personal/next-actions?owner_id=default&limit=5'
```

ระบบจะให้ priority กับ task ที่ถูก block หรือเลยกำหนดก่อน ถ้า goal ยังไม่มี task
จะเสนอ action แรกให้เท่านั้น โดยไม่สร้าง task หรือเปลี่ยน goal อัตโนมัติ

เปลี่ยนรูปแบบการตอบใน conversation ปัจจุบันด้วยข้อความธรรมชาติ เช่น `เข้าโหมดคู่หู`,
`เข้าโหมดทำงาน` หรือ `เปิดโหมดโฟกัส` โหมดจะคงอยู่เฉพาะ owner และ conversation เดิม
จนกว่าจะเปลี่ยนโหมดหรือสั่ง `กลับโหมดปกติ`

- `companion` เน้นความอบอุ่น รับฟังบริบท และถามต่อไม่เกินหนึ่งคำถามเมื่อช่วยได้จริง
- `work` เน้นผลลัพธ์ การลงมือทำ และหลักฐาน
- `focus` ตอบสั้น ให้ next action ทีละอย่าง และลดสิ่งรบกวน

state ของโหมดเป็น bounded in-memory และจะกลับเป็นปกติหลัง restart โดยไม่แก้ character identity
ถาวรใน MCS รวมทั้งไม่ลดข้อกำหนดด้าน safety, confirmation หรือความถูกต้องของ tool result

### Embeddings

`input` รับได้ทั้ง string และ array:

```sh
curl -X POST http://127.0.0.1:8080/v1/embeddings \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3-embedding:0.6b",
    "input": ["ข้อความแรก", "ข้อความที่สอง"]
  }'
```

### Voice Companion

ตรวจสถานะ provider:

```sh
curl http://127.0.0.1:8080/v1/audio/status
```

ถอดเสียงภาษาไทยแบบ local:

```sh
curl -X POST http://127.0.0.1:8080/v1/audio/transcriptions \
  -F 'file=@voice.wav;type=audio/wav' \
  -F 'language=th'
```

สร้างเสียงตอบกลับเป็น WAV:

```sh
curl -X POST http://127.0.0.1:8080/v1/audio/speech \
  -H 'Content-Type: application/json' \
  -d '{"model":"minikun-tts","input":"สวัสดีครับ","voice":"minikun","response_format":"wav"}' \
  --output minikun.wav
```

ส่งเสียงเข้า conversation เดิมและรับทั้งข้อความกับเสียง Base64 กลับมา:

```sh
curl -X POST http://127.0.0.1:8080/v1/audio/voice-turns \
  -F 'file=@voice.wav;type=audio/wav' \
  -F 'language=th' \
  -F 'conversation_id=voice-demo' \
  -F 'voice=minikun'
```

รูปแบบ input ที่รองรับคือ WAV, AIFF, MP3, M4A, MP4 และ CAF ส่วน output รองรับ WAV/AIFF
ระบบใช้ executable และ model path ที่กำหนดตายตัว ไม่รับ shell command จาก request และไม่บันทึก
audio หรือ transcript ใน voice layer; ข้อความจาก `voice-turns` จะเข้าสู่ conversation memory ตามกติกา chat ปกติ

### Personal Knowledge

ค่าเริ่มต้นเปิด root ชื่อ `knowledge` ที่ `~/Documents/Minikun Knowledge` การ index รับเฉพาะ
relative path ภายใน root นี้:

```sh
curl -X POST http://127.0.0.1:8080/v1/knowledge/index \
  -H 'Content-Type: application/json' \
  -d '{"owner_id":"default","root":"knowledge","path":"","recursive":true,"force":false}'
```

ตรวจสถานะและค้นหาโดยตรง:

```sh
curl 'http://127.0.0.1:8080/v1/knowledge/status?owner_id=default'
curl -X POST http://127.0.0.1:8080/v1/knowledge/search \
  -H 'Content-Type: application/json' \
  -d '{"owner_id":"default","query":"สถาปัตยกรรมมินิคุง","limit":5}'
curl 'http://127.0.0.1:8080/v1/knowledge/sources?owner_id=default&limit=100'
```

ระบบแบ่งเอกสารเป็น chunk, เก็บ SHA-256 เพื่อข้ามไฟล์ที่ไม่เปลี่ยน และใช้ `qwen3-embedding:0.6b`
ผ่าน Ollama สำหรับ semantic retrieval พร้อม lexical fallback หาก embedding ใช้งานไม่ได้ ผลค้นหาทุกชิ้นมี
`knowledge://` citation และ top results จะถูกเรียกคืนเข้า knowledge pipeline ของบทสนทนาอัตโนมัติ

รองรับไฟล์ UTF-8 เช่น Markdown, text, JSON, YAML, CSV, properties, source code, HTML, SQL,
log และ iCalendar โดยจำกัดเริ่มต้น 2 MB ต่อไฟล์ ไม่ตาม symlink และไม่อ่าน hidden file, `.env`
หรือไฟล์ credential การลบ source ทำผ่าน `DELETE /v1/knowledge/sources/{id}?owner_id=default`
และลบเฉพาะ index/chunk ใน PostgreSQL ไม่ลบไฟล์ต้นฉบับ หากกำหนด management token ให้ส่ง
`X-Minikun-Knowledge-Token`

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

### Adaptive Companion

ระบบเรียนรู้เฉพาะ response style ที่กำหนดไว้ล่วงหน้า โดยไม่เก็บข้อความแชตดิบ:

- `language`: `th`, `en`
- `response_length`: `concise`, `detailed`
- `response_format`: `bullets`, `steps`, `prose`
- `explanation_level`: `simple`, `technical`
- `tone`: `casual`, `professional`

พฤติกรรมทั่วไปต้องพบอย่างน้อย 3 ครั้งก่อนถูกเลื่อนเป็น preference ส่วนคำสั่งชัดเจน เช่น
“ต่อไปตอบสั้นๆ” ใช้ได้ทันที ค่าที่เรียนรู้เป็นเพียง default และข้อความปัจจุบันมีสิทธิ์เหนือกว่าเสมอ
PostgreSQL เก็บเฉพาะจำนวน observation, คะแนน, dimension และ candidate value

ตรวจสอบ evidence กับ active preferences, ส่ง feedback หรือ reset เฉพาะสิ่งที่เรียนรู้:

```sh
curl 'http://127.0.0.1:8080/v1/adaptation?owner_id=default'
curl -X POST http://127.0.0.1:8080/v1/adaptation/feedback \
  -H 'Content-Type: application/json' \
  -d '{"owner_id":"default","dimension":"response_length","value":"concise","positive":true}'
curl -X DELETE 'http://127.0.0.1:8080/v1/adaptation?owner_id=default'
```

การ reset ไม่ลบ profile, memory หรือ preference ที่ผู้ใช้ตั้งเอง หากกำหนด management token ให้ส่ง
`X-Minikun-Adaptation-Token` ส่วนการล้าง `/v1/user-model` จะล้าง adaptation evidence ด้วย
เพื่อป้องกัน learned preference ถูกสร้างกลับมา

### Communication Assistant

มินิคุงช่วยร่าง (`draft`), ปรับข้อความ (`rewrite`), ตอบกลับ (`reply`) และสรุป
(`summarize`) ได้ผ่าน native tool `communication.assist` หรือ REST API โดยเรียกโมเดลหลักที่ active อยู่
ข้อมูล `content` และ `context` ถูกส่งเข้าโมเดลในฐานะ quoted source material, ไม่ถูกบันทึกเพิ่ม และไม่มี
ความสามารถส่งอีเมล โพสต์ หรือข้อความออกไปเอง ผลลัพธ์ทุกครั้งจึงมี `draftOnly: true` และ
`sendSupported: false` ระบบจะตัดรูปแบบ embedded instruction ที่ตรวจพบออกจาก source ก่อนเข้าโมเดล
และเพิ่ม warning ในผลลัพธ์เพื่อให้ตรวจสอบได้

```sh
curl http://127.0.0.1:8080/v1/communication/status
curl -X POST http://127.0.0.1:8080/v1/communication/assist \
  -H 'Content-Type: application/json' \
  -d '{"owner_id":"default","action":"draft","content":"ขอนัดคุยงานวันศุกร์",\
       "audience":"ทีมงาน","channel":"email","tone":"professional",\
       "language":"th","max_length":800}'
```

รองรับ channel `general`, `email`, `chat`, `sms`, `social`, `document` และ tone
`default`, `casual`, `professional`, `warm`, `concise`, `persuasive`, `empathetic`
หากกำหนด management token ให้ส่ง header `X-Minikun-Communication-Token`

## Memory และ prompt composition

Short-term history ถูกผูกกับ `ConversationId` และเก็บทั้งข้อความฝั่ง user กับ assistant ผ่าน Spring AI JDBC Chat Memory ใน PostgreSQL เมื่อบทสนทนายาวขึ้น ระบบจะสรุปช่วงเก่าแบบ rolling summary ลง `minikun_conversation_summary` แยกตาม `owner_id` และ `conversation_id` แล้วใช้ร่วมกับข้อความล่าสุด ส่วน long-term memory ถูกเก็บในตาราง `minikun_memory` ตาม schema ใน [`memory-schema.sql`](src/main/resources/memory-schema.sql)

Long-term memory รองรับ `EPISODE` สำหรับเหตุการณ์ที่มีความหมายต่อเจ้าของ เช่น ความสำเร็จ
ช่วงยาก หรือจังหวะสำคัญของความสัมพันธ์ โดย reflection จะไม่เก็บอารมณ์ชั่วคราวเป็น episode
ส่วน emotional continuity เก็บเพียงสัญญาณอารมณ์แบบชั่วคราวใน process ไม่เก็บข้อความดิบ และหมดอายุใน 24 ชั่วโมง

Conversation check-in เก็บจำนวนครั้งและ feedback `HELPFUL`, `NOT_NOW`, `WRONG_CONTEXT`,
`STOP_THIS_TOPIC` เพื่อให้เลื่อนหรือหยุดตามเรื่องได้จาก Cockpit หรือ
`POST /v1/personal/conversation-threads/{id}/feedback` การเพิ่มคอลัมน์สำหรับฐานข้อมูลเดิมอยู่ใน
[`V20260905_01__companion_care_feedback.sql`](deploy/migrations/V20260905_01__companion_care_feedback.sql)

ใน request chat ระบบจะโหลด history, summary, companion mode, personal knowledge, memory และ LLM search decision แบบขนานก่อนสร้าง prompt ผ่าน PCS โดยส่ง recent turns เป็นข้อความ `USER`/`ASSISTANT` ตาม role จริง ส่วน rolling summary และ omission note อยู่ใน system context หลังตอบสำเร็จจึงบันทึก user และ assistant พร้อมกันเป็น completed turn เดียว จึงไม่ทิ้ง user message ค้างเมื่อ model ล้มเหลวหรือ stream ถูกยกเลิก Cockpit ส่งทุกคำถามเข้า durable background job เพื่อให้ทำงานต่อได้แม้สลับแอปบนมือถือ ส่วน API หลักยังรองรับ token streaming สำหรับ client ที่ต้องการผลทันที

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
งานที่บันทึกแล้วจะส่ง browser notification ผ่าน Cockpit เมื่อถึงเวลา และรายการที่ตั้งซ้ำแบบ `DAILY` หรือ `WEEKLY`
จะ fallback ไป ntfy เมื่อไม่มี browser ที่เชื่อมต่ออยู่
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
งดส่งเฉพาะ notification ที่ agent เริ่มเอง เช่น task follow-up และ daily briefing ใน quiet hours
ส่วน daily briefing จะรวมสภาพอากาศ นัดหมายจาก local/external calendar งานที่ลงมือได้ และสิ่งที่กำลังรอ
ส่งไม่เกินหนึ่งครั้งต่อวัน และเก็บสถานะการส่งใน PostgreSQL หากแหล่งข้อมูลภายนอกส่วนใดล้ม
briefing จะใช้ข้อมูลส่วนที่เหลือต่อโดยไม่ล้มทั้งฉบับ

### Local Computer Agent

`computer.local` เข้าถึงได้เฉพาะ logical roots ที่กำหนดใน `minikun.computer.roots`
โดย path ทุกค่าต้องเป็น relative path ภายใน root เท่านั้น รายชื่อ root ปัจจุบันกำหนดใน `application.properties`

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

### Closed-loop Personal Agent

Personal Loop เชื่อม goal, task, agent run, Guardian และ conversation provenance ให้เป็นวงจร
`ทบทวน → เสนอ → ยืนยัน → ลงมือ → ติดตามผล → เรียนรู้` โดยทุกข้อมูลแยกตาม `owner_id`
และไม่เก็บ raw prompt หรือ hidden reasoning ใน explainability trace

- Weekly Review สรุปผลงาน ความเสี่ยง และสร้าง next-action proposal สูงสุด 3 รายการ
  แต่จะไม่เปลี่ยน task จนกว่าจะยืนยัน proposal แยกรายการ
- Outcome Learning เก็บ lifecycle ของคำแนะนำและเรียนรู้จากคะแนน 1–5 ที่เจ้าของให้เองเท่านั้น
- Universal Inbox รับ `TEXT`, `VOICE`, `LINK` หรือ `FILE`, จัดประเภทและแสดง preview ก่อน commit
- Safe Automation รองรับ trigger/action แบบ allowlist โดยระดับความเสี่ยงคำนวณจาก server;
  action ที่เปลี่ยน state เช่น `CREATE_TASK` ต้องยืนยันทุกครั้ง
- Incident Commander รวม Guardian findings เป็น fingerprint, probable cause และ incident timeline
- Explainability แสดง source identifier, tool และ routing decision ที่ใช้จริง โดยไม่เก็บเนื้อหา retrieval
- Personal Timeline รวม lifecycle event กับ task, goal และ agent run เป็น read model เดียว
- Personal Experiment เก็บสมมติฐาน วิธีทดลอง metric, baseline/target, check-in และผลสรุป
  โดยเชื่อม lifecycle เข้ากับ Outcome Learning และ Timeline อัตโนมัติ

เปิด Minikun Web ในเครือข่ายภายในได้ที่:

```text
http://127.0.0.1:8080/cockpit
https://127.0.0.1:8443/cockpit/
https://mini-kun:8443/cockpit/
```

การ deploy บน macOS จะสร้าง Local CA และใบรับรอง HTTPS ที่มี SAN สำหรับ `mini-kun`,
`mini-kun.local`, `localhost`, loopback, LAN IP และ Tailscale IP ที่ตรวจพบ โดยยังคง HTTP
พอร์ต `8080` ไว้สำหรับ health probe และดาวน์โหลด public CA ได้จาก
`http://<LAN-IP>:8080/v1/system/https/ca` หากใช้ iPhone ให้ติดตั้ง profile ที่ดาวน์โหลด
จากนั้นเปิด full trust ที่ Settings > General > About > Certificate Trust Settings ก่อนเปิด HTTPS

หน้า Chat ส่งงานไปที่ `/v1/chat/background` แล้วให้ server ทำต่อแม้สลับไปใช้แอปอื่น โดยยังใช้
model, memory, search, vision, native tools และ confirmation policy ชุดเดียวกับ API หลัก
เมื่อเสร็จจะส่ง browser notification ผ่าน Cockpit และ fallback ไป ntfy เมื่อไม่มี browser ที่เชื่อมต่ออยู่
หน้า Chat จะรับผลกลับอัตโนมัติเมื่อเปิดค้างไว้หรือกลับมาอีกครั้ง
สถานะและ payload ของงานเก็บใน PostgreSQL เป็นเวลา 24 ชั่วโมง งานที่ค้างระหว่าง restart จะทำต่ออัตโนมัติ
และงานที่ failed/cancelled เริ่มใหม่ได้ด้วย `POST /v1/chat/background/{jobId}/resume`
งานตอบเริ่มที่สถานะ `queued` แล้วเข้าสู่ worker ตาม `MINIKUN_CHAT_BACKGROUND_MAX_ACTIVE`;
งานเกิน `MINIKUN_CHAT_BACKGROUND_MAX_QUEUED` จะถูกปฏิเสธอย่างปลอดภัยแทนการกอง virtual thread ไม่จำกัด
การส่งซ้ำจาก network retry ใช้ `X-Idempotency-Key` เดิมเพื่อคืน job เดิม และคำตอบข้อความจะถูกส่งกลับก่อน
งานภาพประกอบที่วางแผนไว้จะเข้า image queue แยกต่างหาก จึงไม่ทำให้ผู้ใช้รอ TinyGrad ก่อนเห็นข้อความ
ถ้าต้องการ fallback บนมือถือ ให้ติดตั้งแอป ntfy และ subscribe topic จาก `MINIKUN_NTFY_REMINDER_TOPIC`
รองรับการแนบ JPEG/PNG/WebP, ไฟล์ข้อความ, การถอดเสียงผ่าน `/v1/audio/transcriptions`
และอ่านคำตอบผ่าน `/v1/audio/speech`

หน้าเว็บไม่ฝัง token ลง bundle และเก็บ token ที่กรอกไว้เฉพาะ `sessionStorage` ของแท็บปัจจุบัน
ส่วนรายการบทสนทนาและข้อความสำหรับแสดงผล sync ผ่าน PostgreSQL โดยใช้ device session แบบ
`HttpOnly + Secure + SameSite=Strict` จึงไม่ต้องมี user/password หรือส่ง secret ให้ JavaScript
และยังเชื่อมผ่าน origin เดียวกับ Minikun API พร้อม confirmation policy ฝั่ง server เหมือนเดิม

การเชื่อมอุปกรณ์ครั้งแรกให้เปิด `https://127.0.0.1:8443/cockpit/` จากเครื่อง Mac ที่รัน service
ระบบจะสร้าง trusted device สำหรับ origin ของเครื่องหลักให้ครั้งเดียว จากนั้นไปที่
Settings → Device Sync → เชื่อมอุปกรณ์ใหม่ แล้วใช้ iPhone สแกน QR หรือเปิด pairing link
ซึ่งชี้ไป `https://mini-kun:8443` ผ่าน Tailscale แต่ละ origin มี session cookie แยกกัน
ขณะที่บทสนทนาใช้ owner และ PostgreSQL ชุดเดียวกันจึงยัง sync ถึงกัน รหัสมีอายุ 2 นาทีและใช้ได้ครั้งเดียว
สามารถดู last seen และถอนสิทธิ์อุปกรณ์อื่นแยกรายเครื่องได้จากหน้าเดียวกัน เมื่ออุปกรณ์เครื่องสุดท้าย
ถูกตัดการเชื่อมต่อ Mac เครื่องหลักจะ bootstrap ใหม่ได้อีกครั้ง ประวัติเดิมจาก `localStorage`
จะถูก import ครั้งแรกโดยอัตโนมัติ

Eval Lab อยู่ในแท็บ “ระบบ” ของ Cockpit และเรียกตรงผ่าน API ได้โดยไม่สร้างคำตอบหรือเรียก tool:

```sh
curl -H "X-Minikun-Personal-Token: $MINIKUN_EVAL_MANAGEMENT_TOKEN" \
  'http://127.0.0.1:8080/v1/evals/turn-plans/baseline'

curl -H "X-Minikun-Personal-Token: $MINIKUN_EVAL_MANAGEMENT_TOKEN" \
  'http://127.0.0.1:8080/v1/evals/turn-plans/quality?owner_id=default&limit=100'
```

quality report แสดง approval rate แยกตาม intent/execution และ shadow signals จาก feedback เชิงลบ
เพื่อเสนอสิ่งที่ควรทดลองปรับเท่านั้น ระบบจะไม่เปลี่ยน routing policy เอง

ตัวอย่างสร้างและเริ่ม Personal Experiment:

```sh
curl -X POST -H 'Content-Type: application/json' \
  -H "X-Minikun-Personal-Token: $MINIKUN_PERSONAL_LOOP_TOKEN" \
  -d '{"conversationId":"cockpit","title":"โฟกัสก่อนเปิดแชต","hypothesis":"ช่วงเงียบช่วยให้งานคืบหน้า","protocol":"ทำงานสำคัญ 30 นาทีก่อนเปิดแชต","metricName":"นาทีโฟกัส","metricUnit":"นาที","direction":"INCREASE","baselineValue":15,"targetValue":45,"durationDays":7}' \
  'http://127.0.0.1:8080/v1/personal/experiments?owner_id=default'

curl -X POST -H 'Content-Type: application/json' \
  -H "X-Minikun-Personal-Token: $MINIKUN_PERSONAL_LOOP_TOKEN" \
  -d '{"action":"START"}' \
  'http://127.0.0.1:8080/v1/personal/experiments/<experiment-id>/transition?owner_id=default'
```

ตัวอย่าง capture และ commit ผ่าน Universal Inbox:

```sh
curl -X POST -H 'Content-Type: application/json' \
  -H "X-Minikun-Personal-Token: $MINIKUN_PERSONAL_LOOP_TOKEN" \
  -d '{"conversationId":"home","inputType":"TEXT","content":"todo: ตรวจ backup","metadata":{}}' \
  'http://127.0.0.1:8080/v1/personal/inbox?owner_id=default'

curl -X POST -H "X-Minikun-Personal-Token: $MINIKUN_PERSONAL_LOOP_TOKEN" \
  'http://127.0.0.1:8080/v1/personal/inbox/<item-id>/commit?owner_id=default'
```

API หลักอยู่ใต้ `/v1/personal`:

- `/weekly-reviews` และ `/weekly-reviews/proposals/{id}/decision`
- `/outcomes`, `/outcomes/insights` และ `/outcomes/{id}/transition`
- `/inbox`, `/automations`, `/automations/runs`
- `/experiments`, `/experiments/{id}/transition`, `/experiments/{id}/check-ins`
  และ `/experiments/{id}/evaluate`
- `/incidents`, `/explanations` และ `/timeline`

schema อยู่ใน `personal-loop-schema.sql` และใช้ token จาก `MINIKUN_PERSONAL_LOOP_TOKEN`
(fallback ไปยัง memory management token) การ schedule ค่าเริ่มต้นคือ Weekly Review วันอาทิตย์
19:00 และ automation/incident poll ทุก 60 วินาที

รายละเอียดเพิ่มเติม:

- [Conversation memory](docs/conversation-memory.md)
- [Provider Composition System](docs/pcs.md)
- [Conversation policy and relationship continuity](docs/conversation-policy-runtime.md)
- [Logic reliability, recovery และชุดประเมินบทสนทนาไทย](docs/logic-reliability.md)
- [Visual Companion](docs/visual-companion.md)

## การทดสอบ

รัน test suite:

```sh
./mvnw test
```

การทดสอบ application context ใช้ in-memory chat memory และปิด JDBC persistence บางส่วน จึงไม่จำเป็นต้องมี PostgreSQL สำหรับ unit/context tests ทั้งหมด แต่การทดสอบการทำงานจริงของ Ollama, PostgreSQL, Valkey และ SearXNG ต้องเตรียม service เหล่านั้นเอง

## Deploy บน macOS

สคริปต์ deploy จะ build JAR, สร้าง/ต่ออายุใบรับรอง local HTTPS, ติดตั้ง LaunchAgent
และตรวจสอบ health endpoint ทั้ง HTTP `8080` กับ HTTPS `8443`:

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

ติดตั้ง Voice runtime ครั้งแรกก่อน deploy (ดาวน์โหลด Whisper Q4 ประมาณ 464 MB และติดตั้ง
`mlx-whisper` ใน venv แยกใต้ Application Support):

```sh
./minikun_agent/deploy/install-voice-runtime.sh
```

การ deploy ครั้งถัดไปจะคัดลอก Python adapter รุ่นล่าสุดให้ แต่ไม่ดาวน์โหลดโมเดลซ้ำ

หาก LaunchAgent รันจาก external volume แล้วพบ `Operation not permitted` ให้ตรวจสอบสิทธิ์ Privacy & Security ของ macOS สำหรับ process ที่ launchd เรียกใช้งาน

## หมายเหตุด้าน production

- deployment จะรัน [`deploy/migrate-database.sh`](deploy/migrate-database.sh) ก่อน restart เพื่อใช้ migration ที่มี version; `spring.sql.init.mode=always` ยังคงไว้สำหรับ local bootstrap จนกว่า legacy schema ทั้งหมดจะย้ายเข้าระบบ migration
- อย่า commit secret เช่น database password หรือ API key ลง repository ควรส่งผ่าน environment variables
- ตรวจสอบ model name ให้ตรงกับ model ที่ติดตั้งใน Ollama/compatible backend
- search decision ยังคงใช้ mode `llm` โดยจำผล query/context เดิม 5 นาทีและ fallback ภายใน 2 วินาที จึงไม่ต้องขยาย keyword dictionary; การเปิด `MINIKUN_MEMORY_REFLECTION_ENABLED` ยังเพิ่มการเรียก task model หลังจบบทสนทนา
