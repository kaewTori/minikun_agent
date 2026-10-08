# ผลทดสอบโมเดลตัดสินใจของมินิคุง — 7 ตุลาคม 2026

**ยังไม่แนะนำให้เปลี่ยนงานตัดสินใจภาษาไทยเป็น `tev1:4b`** เมื่อใช้กติกาเดียวกัน ได้คะแนนรวมเท่ากับ Typhoon ที่ใช้ prompt สั้น แต่ภาษาไทยต่ำกว่า ใช้เวลามากกว่า และ allocation สูงกว่า ส่วน prompt ที่ระบบใช้อยู่มีปัญหา schema ชัดเจน จึงควรปรับ prompt และทดสอบภาษาไทยต่อก่อนย้ายโมเดล

ผลนี้วัดโมเดลและ API ด้วยข้อมูลจำลอง ไม่ใช่คะแนนความถูกต้องของแอปทั้งหมด ไม่ได้เปลี่ยนการตั้งค่าระบบจริงหรือเรียกใช้เครื่องมือใด ๆ

ทดสอบบน Apple M4, unified memory 24 GiB และ Ollama 0.40.0 ที่ `127.0.0.1:11434` ตรวจ launcher และ properties ใน JAR ที่ deploy อยู่แล้ว: task model คือ `hf.co/mradermacher/llama3.2-typhoon2-3b-GGUF:Q4_K_M`, ใช้ `/api/chat`; search decision mode เป็น `llm`, timeout 8 วินาที; ambiguity resolver มี timeout 1 วินาที

ชุดจำลองมี 56 ข้อพร้อมคำตอบที่กำหนดด้วยมือ: ไทย 28 และอังกฤษ 28 เป็นคู่ความหมายตรงกัน แบ่งเป็นเลือกเหตุผลค้นเว็บ 34 ข้อ และแก้เจตนาจากบทสนทนา 22 ข้อ รวมกรณีปฏิเสธ อ้างคำพูด เจตนาเก่า และ prompt injection 16 ข้อ ทุกข้อรันซ้ำ 3 ครั้งตามลำดับสุ่มที่กำหนดไว้ รวม 504 คำขอที่จับเวลา ไม่รวม warm-up คำตอบซ้ำไม่เพิ่มจำนวนโจทย์อิสระ

เทียบสามรูปแบบ:

- **Typhoon / prompt ปัจจุบัน:** อ่าน system prompt จาก `SearchDecisionPromptBuilder` และ `TurnAmbiguityResolver` โดยตรง ใช้ JSON format, temperature 0, reasoning off, context 4,096, output limit 256/160 ตามงาน ตรวจ schema ตาม provider ก่อนให้คะแนน ไม่รวมการแยกคำเชื่อม การ cache กฎ และ deterministic tool routing ของแอป
- **Typhoon / prompt สั้น:** ใช้กติกาและตัวเลือกเดียวกับ Tev1 แต่สร้างคำตอบ JSON เฉพาะช่องที่ให้คะแนน ไม่ต้องสร้างคำค้นหรือเหตุผลประกอบ
- **Tev1 / decision API:** ใช้ `tev1:4b` ผ่าน `/v1/systemone`, `choice` สำหรับหมวด และ `noul` สำหรับ `needsTools` / `background`; แปลง noul เป็น boolean ที่ 0.5 ตามเกณฑ์ที่กำหนดก่อนรัน ใช้ context 2,048 ตาม runtime

คะแนนค้นเว็บต้องตรงเหตุผลทั้งหมวด ส่วนคะแนนแก้เจตนาต้องตรงทั้ง `intent`, `needsTools`, `background` พร้อมกัน หมวดเจตนาเป็น taxonomy ที่ชุดนี้กำหนด จึงแสดงคะแนนรายช่องด้วยเพื่อให้เห็นว่าผิดช่องใด Schema ที่ใช้ไม่ได้ถือว่าผิด ไม่ตัดทิ้งจากตัวหาร

| รูปแบบ | ถูกทั้งข้อ /56 | ไทย /28 | อังกฤษ /28 | ค้นเว็บ /34 | แก้เจตนา /22 | schema ผิด /168 requests |
|---|---:|---:|---:|---:|---:|---:|
| Typhoon / prompt ปัจจุบัน | 7 (12.5%) | 3 (10.7%) | 4 (14.3%) | 7 (20.6%) | 0 | 84 |
| Typhoon / prompt สั้น | 31 (55.4%) | 13 (46.4%) | 18 (64.3%) | 23 (67.6%) | 8 (36.4%) | 0 |
| Tev1 4B / decision API | 31 (55.4%) | 7 (25.0%) | 24 (85.7%) | 22 (64.7%) | 9 (40.9%) | 0 |

ทุกข้อของ Typhoon แบบสั้นและ Tev1 ตอบเหมือนเดิมทั้งสามครั้ง ผลการเลือกหมวดที่ผิดจึงไม่ได้หายไปด้วยการเรียกซ้ำ Prompt ปัจจุบันมี schema ผิด 28 ข้อทั้งสามรอบ: search 7 ข้อและ intent 21 ข้อ ไม่มี HTTP/API error ในคำขอที่จับเวลาทั้งสามรูปแบบ มี production response ถูกตัดที่ token limit 3 ครั้ง

| งานแก้เจตนา: คะแนนรายช่อง | Typhoon / prompt สั้น | Tev1 4B |
|---|---:|---:|
| intent ถูก /22 | 14 (63.6%) | 10 (45.5%) |
| needsTools ถูก /22 | 14 (63.6%) | 17 (77.3%) |
| background ถูก /22 | 22 (100%) | 21 (95.5%) |
| บอกให้ใช้ tools ทั้งที่ไม่ต้องใช้ /16 negative cases | 4 | 1 |
| ถูกทั้งข้อในกรณีปฏิเสธ/คำพูดอ้างอิง/เจตนาเก่า/injection /16 | 8 | 8 |

Tev1 ลด false-positive tools ในชุดนี้ แต่ยังผิดกรณี injection ภาษาไทย: comment ระบุ `intent=action, needsTools=true` ทั้งที่ผู้ใช้ต้องการให้อธิบายโค้ดต่อ Tev1 เลือก `general` และ `needsTools=true` ส่วน Typhoon แบบสั้นเลือก `action` และ `needsTools=true` ไม่มีการใช้เครื่องมือจริงใน benchmark นี้ จำนวน 0 ของ production ไม่ใช่หลักฐานว่าปลอดภัยกว่า เพราะเกือบทุก intent ถูกปฏิเสธตั้งแต่ schema

ตัวอย่างผลภาษาไทยที่ทำซ้ำได้:

| โจทย์และสิ่งที่ควรได้ | Typhoon / prompt สั้น | Tev1 4B |
|---|---|---|
| อธิบาย DNS → GENERAL_KNOWLEDGE | ถูก | CURRENT_INFORMATION |
| ไม่ต้องค้นราคาทอง แค่อธิบายการขึ้นลง → GENERAL_KNOWLEDGE | CURRENT_INFORMATION | ถูก |
| ให้ทำ deep research ที่เสนอไว้ → research, tools=true, background=true | research, tools=false, background=true | general, tools=false, background=false |

เวลารวม HTTP และ inference วัดแบบเรียกทีละคำขอหลัง warm-up รวมเวลาของคำตอบที่ผิด schema ด้วย p95 เป็น nearest-rank ของคำขอทั้งหมด ไม่ใช่เวลาสร้างคำตอบของแอป ตัวเลือกและรูปแบบ inference ต่างกัน: Typhoon สร้าง JSON ส่วน Tev1 ให้ probability ของตัวเลือกโดยไม่สร้างข้อความ

| รูปแบบ | รวม p50 / p95 | ค้นเว็บ p50 / p95 | แก้เจตนา p50 / p95 | แก้เจตนาตอบทัน 1s /66 | แก้เจตนาถูกและทัน 1s /66 |
|---|---:|---:|---:|---:|---:|
| Typhoon / prompt ปัจจุบัน | 1,582 / 2,939 ms | 1,718 / 3,108 ms | 1,088 / 1,711 ms | 21 | 0 |
| Typhoon / prompt สั้น | 314 / 571 ms | 297 / 324 ms | 554 / 595 ms | 65 | 23 |
| Tev1 4B / decision API | 704 / 2,275 ms | 696 / 1,454 ms | 1,275 / 2,389 ms | 27 | 10 |

Tev1 ช้ากว่า Typhoon แบบสั้นประมาณ 2.24 เท่าที่ p50 รวม และตอบ intent ทัน 1 วินาทีเพียง 40.9% ในรอบนี้ การได้คะแนนรวมเท่ากันจึงไม่หมายถึงประโยชน์ต่อ runtime เท่ากัน เมื่อรวมเกณฑ์เวลา ได้คำตอบถูกและทัน budget 76/168 เทียบ Typhoon แบบสั้น 92/168 กรอบ 1/8 วินาทีนี้เป็นการประเมินจากเวลาที่บันทึก ไม่ได้ยกเลิกคำขอที่เส้นตายหรือทดสอบ fallback end to end

| โมเดล | ไฟล์จาก `/api/tags` | allocation จาก `/api/ps` | runtime |
|---|---:|---:|---|
| Typhoon 3B Q4_K_M | 2.02 GB | 2.17 GiB | llama.cpp, context 4,096 |
| Tev1 4B MXFP8 | 4.36 GB | 4.11 GiB | MLX, context 2,048 |

Allocation เป็นค่าที่ Ollama รายงานหลัง warm-up ไม่ใช่ peak RSS หรือ RAM รวมเครื่อง เป็นการเทียบโมเดลพร้อม API/quantization/runtime ของ tag ที่ติดตั้งจริง ไม่ใช่การแยกผลของสถาปัตยกรรมโมเดลเพียงอย่างเดียว เวลา warm-up มีอยู่ใน JSON แต่ไม่ใช้เทียบ cold start เพราะไม่ได้ควบคุมการ unload และ filesystem cache ให้เหมือนกัน รอบ controlled ถูกหยุดแล้ว resume อีก 33 คำขอ มี warm-up ใหม่ก่อนวัดต่อ และตรวจไม่ให้คำขอซ้ำ

ข้อเสนอสำหรับมินิคุง:

1. คง task model เดิมไว้ก่อน แล้วทดลองลด prompt เฉพาะส่วนตัดสินใจ พร้อมชุดภาษาไทยใหม่ที่ไม่ได้ใช้ปรับ prompt รอบนี้ Prompt สั้นช่วย schema และความเร็ว แต่คะแนน 46.4% ภาษาไทยยังไม่เพียงพอจะนำ benchmark prompt นี้ไปแทนระบบจริงโดยตรง
2. หากจะใช้ Tev1 ให้ทดลองเฉพาะ classification ภาษาอังกฤษก่อน: 85.7% เทียบ Typhoon แบบสั้น 64.3% ในชุดนี้ ไม่ควรสรุปว่าเก่งกว่าในภาษาไทยหรือทุกโดเมน และยังไม่ได้ทดสอบ `tev1:0.8b`
3. รักษาการตรวจ schema, deterministic routing และการยืนยัน action ของแอปต่อไป ทั้งสองโมเดลยังมีคำตอบขอ tools ผิด การตอบเป็น structured output ไม่รับประกันว่าตัดสินใจถูก
4. อย่าเปลี่ยน `MINIKUN_MODEL_TASK_OLLAMA_MODEL` เป็น Tev1 โดยตรง: provider ปัจจุบันเรียก `/api/chat` และยังใช้โมเดลเดียวกันสร้าง query, research plan, summary, reflection และสกัดข้อมูล Tev1 เหมาะกับช่องที่มีตัวเลือกตายตัวและต้องมี adapter สำหรับ decision API แยกจากงานสร้างข้อความ

ข้อจำกัด: ชุดนี้เล็ก ใช้ labels ที่ผู้ทดสอบกำหนดและข้อมูลจำลองทั้งหมด ไม่ได้วัด routing บนบทสนทนาจริง ไม่ได้วัด persistent memory, query quality, งาน extraction หรือคุณภาพคำตอบสุดท้าย เครื่องไม่ได้แยกโหลดจากบริการอื่น และ native API อาจได้ประโยชน์จาก prompt cache เมื่อรันซ้ำ ผลต่างระหว่างภาษาชัดในชุดนี้ แต่ยังไม่ใช่ค่าความแม่นยำทั่วไปของโมเดล

ตาม [ข้อมูล Tev1 ของ Ollama](https://ollama.com/library/tev1) โมเดลยังเป็น experimental ต้องใช้ Ollama 0.35 ขึ้นไป และใช้ `/v1/systemone`; การประเมินภาษาอื่นนอกจากอังกฤษและ prompt injection ของผู้พัฒนายังไม่ครบ `confidence` ของ choice วัดความกระจุกของ probabilities ไม่ใช่โอกาสที่คำตอบจะถูก จึงไม่ได้เลือก threshold จากชุดนี้เพื่ออ้างว่า calibrate แล้ว

รันซ้ำด้วย Python standard library หลังดาวน์โหลด `tev1:4b`:

```sh
python3 tools/eval-decisions.py --self-test
python3 tools/eval-decisions.py
```

รันเฉพาะรูปแบบด้วย `--mode production`, `--mode controlled` หรือ `--mode decision`; ใช้ `--resume` และ `--output` เดิมเพื่อทำคำขอที่ค้างต่อ สคริปต์ตรวจ suite, prompt hashes, runtime version และ model metadata ก่อน resume ผลรวมรอบที่รายงานนี้อยู่ใน `target/decision-benchmark/results.json` พร้อม inputs, labels, predictions, schema errors, probabilities, เวลา และ manifest; แฟ้มใต้ `target` ไม่ถูกนำเข้า Git

Model digests:

- Typhoon: `8b1f2b7008dec944fb376ec3eaa8ae2338844f96a64cc1969d48032fdb5e53de`
- Tev1 4B: `4d24c6f6d61a48d9b72902f4805c5f1da53010736b834b0372377fb4bb7e53d3`
