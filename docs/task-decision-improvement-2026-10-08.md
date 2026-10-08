# ปรับ task model และทดสอบซ้ำ — 8 ตุลาคม 2026

ปรับโค้ดให้ Typhoon ตัวเดิมตัดสินใจดีขึ้นและเร็วขึ้น: ชุดเดิมถูก 37/56 (66.1%) จาก 7/56 (12.5%) เวลามัธยฐานลดจาก 1,582 เป็น 381 ms ส่วนโจทย์ไทยชุดยืนยันใหม่ถูก 6/12 (50%) จาก 1/12 (8.3%) ไม่เปลี่ยนโมเดลหรือ quantization และยังไม่ได้ deploy โค้ดเข้าบริการที่พอร์ต 8080

สิ่งที่เปลี่ยน:

- ส่ง JSON Schema แบบ object ให้ Ollama ทั้ง native และ OpenAI-compatible API บังคับ output เป็น enum ช่องเดียว: search ใช้ `reason`, ambiguity ใช้ `intent` จำกัด output 32 tokens ทั้งสองงาน
- ตัด reason แบบข้อความและ confidence ที่โมเดลเดาออกจาก ambiguity response แอปกำหนด `needsTools` เฉพาะ action/search/research และ background เฉพาะ research; unknown intent หรือ field ที่เกิน schema ถูกปฏิเสธ ค่า confidence ของ turn ที่กำกวมยังเป็นค่ากฎเดิม 0.45 ไม่ได้อ้างว่า calibrate แล้ว
- จัด input เป็น JSON ที่มี key order คงที่และรูปแบบอ่านง่าย แยก context กับ latestMessage ลดความสับสนระหว่างคำสั่งกับข้อความที่นำมาอ้าง
- เอา `/no_think` ที่ยัดไว้ในสอง decision prompts ออก และจัดการ prefix ที่ provider กลางสำหรับโมเดลที่ไม่ใช่ Qwen เพื่อครอบคลุม caller อื่นด้วย Qwen ยังใช้ reasoning control เดิม
- ใช้ query planner ที่มีอยู่ต่อ ให้ classifier เลือกหมวดเท่านั้น เมื่อค้นจริง เก็บคำขอเดิมเป็น primary query เพื่อรักษาชื่อ งบ สถานที่ และเงื่อนไข; follow-up ใช้ continuity planner เดิม และภาพใช้ตัวตัด request prefix เดิม ยังคงการเลือก primary/alternate สำหรับคำขอหลายเรื่องที่แบ่ง clause ได้ ไม่แบ่งคำพูดอ้างอิง/คำปฏิเสธเป็นคำสั่งใหม่
- เปิด fast path ที่มีอยู่ใน LLM mode แบบอนุรักษนิยม: คำทักทายและคำอธิบายทั่วไปที่ชัดเจนไม่ต้องเรียกโมเดล ข้อความมีบริบท ข้อมูลสด คำอ้างอิง และคำแนะนำจริงยังส่งให้ semantic classifier โดยไม่ใช้ keyword positive-search override
- การต่อคำอธิบายโค้ดที่รู้เจตนาจากคำขอผู้ใช้ก่อนหน้าแล้ว และการต่อเรื่องแต่ง ไม่เรียกโมเดลมาจัดหมวดใหม่ ไม่เปิด tools ซ้ำหลัง verified routing; การต่อ action ของเซิร์ฟเวอร์ยังผ่าน resolver ตามเดิม สิทธิ์และการยืนยัน action ของ runtime ไม่ได้ย้ายไปให้โมเดล

**ผลวัดโมเดล/provider**

ใช้ Typhoon `hf.co/mradermacher/llama3.2-typhoon2-3b-GGUF:Q4_K_M`, Ollama 0.40.0, Apple M4 / RAM 24 GiB และ context 4,096 เหมือนเดิม อุณหภูมิ 0 / reasoning off รันทุกโจทย์ซ้ำ 3 ครั้งหลัง warm-up ลำดับสุ่มคงที่ ไม่มีเครื่องมือทำงานจริง เกณฑ์ labels เดิมไม่เปลี่ยน Schema errors ถือว่าผิดและรวมเวลาไว้ด้วย

| ชุดข้อมูล | ถูกก่อน → หลัง | p50 ก่อน → หลัง | p95 ก่อน → หลัง | schema errors ก่อน → หลัง |
|---|---:|---:|---:|---:|
| ชุดเดิม ไทย/อังกฤษ 56 ข้อ | 7/56 → 37/56 | 1,582 → 381 ms | 2,939 → 428 ms | 84/168 → 0/168 |
| ชุด validation ไทย 24 ข้อ | 2/24 → 12/24 | 1,791 → 392 ms | 2,265 → 445 ms | 36/72 → 0/72 |
| ชุดยืนยันไทยใหม่ 12 ข้อ | 1/12 → 6/12 | 1,670 → 388 ms | 2,117 → 415 ms | 15/36 → 0/36 |

ชุดเดิมหลังแก้: ไทย 18/28 (64.3%), อังกฤษ 19/28 (67.9%); เลือกเหตุผลค้นเว็บ 26/34 (76.5%), แก้เจตนาครบทั้งหมวด/tools/background 11/22 (50%) เมื่อเทียบ Typhoon แบบ prompt สั้นในรายงาน 7 ตุลาคม ซึ่งได้ 31/56 (55.4%), ไทย 13/28 (46.4%) และ p50 314 ms รุ่นนี้คะแนนดีกว่าแต่เพิ่มเวลาประมาณ 67 ms ที่ p50 ไม่ได้อ้างว่าเร็วกว่าทุก prompt ที่ทดลอง

หลังแก้ 276 คำขอที่จับเวลาไม่มี API/schema error ไม่มี output ที่ถูกตัดด้วย token limit ทุกข้อให้ผลตรงกันทั้งสามรอบ และคำตอบที่ถูกทุกครั้งอยู่ใน budget 1 วินาทีสำหรับ ambiguity / 8 วินาทีสำหรับ search ไม่รวม cold load จึงไม่ได้รับประกัน deadline ตอนโมเดลถูก unload หรือเครื่องมีโหลดสูง

ชุดเดิมเป็น development set และชุดไทย 24 ข้อถูกดูระหว่างการปรับ prompt จึงไม่ใช่ held-out score ชุดยืนยันไทย 12 ข้อกำหนด labels ก่อนรันและไม่ได้ใช้ปรับ prompt หลังเห็นผล ตัวเลขก่อนของชุดเดิมมาจากรายงาน 7 ตุลาคม ส่วนก่อน/หลังของชุดไทยใหม่วัดในรอบนี้ด้วย frozen prompts เดิม วันที่ใน input คงไว้ที่ `2026-10-07` เพื่อเทียบกันได้ ชุดเล็กและข้อมูลจำลองยังไม่ใช่ความแม่นยำทั่วไปบนบทสนทนาจริง

ตารางเป็นการเรียกโมเดล/API ด้วย prompt, schema และ input format จาก source ไม่รวม fast path, cache, split-clause aggregation หรือเวลาของ ChatService ทั้งระบบ ตรวจเส้นทาง Java จริงแยกด้วย live native tests และ regression tests ดังนั้นไม่ควรใช้ตัวเลขนี้อ้างว่าแอปทั้งตัวตอบเสร็จใน 381 ms

**ข้อผิดพลาดที่ยังเหลือ**

โมเดลล้วนยังผิดบางโจทย์ quote, omitted target, การแก้เอกสาร และ action ภาษาไทย ชุดยืนยันใหม่ยังผิด 6/12 จึงยังไม่ควรใช้ผลโมเดลเป็นตัวตัดสินเพียงอย่างเดียวสำหรับการเปิด execution path

มี raw classifier false-positive tools ใน code-comment injection 2 ข้อของชุดเดิม และ 1 ข้อของ validation ทุกครั้งที่รันซ้ำ โค้ด planner จึงมี regression test ให้ `ต่อ`, `ทำต่อเลย`, `continue` ในคำขออธิบายโค้ดที่ชัดเจนใช้เส้นทางเดิมและไม่เปิด tools แม้ stub model จะตอบ action ขณะเดียวกันยังเรียก resolver สำหรับการต่อ action จริง การทดสอบนี้ครอบคลุมรูปแบบที่ระบุ ไม่ใช่หลักฐานว่ากัน prompt injection ได้ทุกรูปแบบ ชุดยืนยันใหม่ไม่พบ false-positive tools แต่มีเพียง 12 ข้อ

**การตรวจโค้ด**

`./mvnw -q -Dminikun.eval.live=true test` ผ่าน: 1,380 tests, failures 0, errors 0, skipped 11 รวม live Java → Ollama สำหรับ native schema, การอธิบายโค้ด และการรักษาคำขอค้นปัจจุบัน และ regression เรื่อง schema serialization, Qwen/non-Qwen control, unknown intent, verified tools, query constraints, mixed requests, quote splitting และ search fast paths หลังปรับขอบเขต fast path เพื่อรักษา rule mode ตรวจ targeted tests ซ้ำอีกครั้ง

Python self-test และ `git diff --check` ผ่าน ไม่เพิ่ม dependency และไม่เปลี่ยนการตั้งค่าโมเดล/timeout ของบริการจริง

รันทดสอบปัจจุบันซ้ำ:

```sh
python3 tools/eval-decisions.py --self-test
python3 tools/eval-decisions.py --mode production --suite all \
  --output target/decision-improvement/after.json
python3 tools/eval-decisions.py --mode production --suite confirmation \
  --output target/decision-improvement/after-confirmation.json
./mvnw -q -Dminikun.eval.live=true test
```

ใช้ `--legacy-prompts target/decision-improvement/before-prompts.json` เพื่อเทียบ prompts เดิมที่บันทึกไว้ในเครื่อง ผลรายข้อ, probabilities ของรอบ Tev1 เดิม, เวลา, outputs, errors และ hashes อยู่ใต้ `target/decision-benchmark/` กับ `target/decision-improvement/` ผลรอบทดลองแต่ละ prompt เก็บแยกจากผลสุดท้าย ไม่รวมคะแนนข้ามเวอร์ชัน prompt
