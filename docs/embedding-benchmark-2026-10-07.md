# ผลทดสอบ embedding ของมินิคุง — 7 ตุลาคม 2026

ข้อเสนอ: เลือก `embeddinggemma-2:270m-mxfp8-text` เป็นตัวทดลองต่อ หากต้องการลดเวลาและทรัพยากร แต่ยังไม่มีหลักฐานเพียงพอให้เปลี่ยนระบบใช้งานจริงทันที Qwen ที่ปรับ query prefix ได้คะแนนอันดับแรกสูงที่สุดในชุดนี้ ส่วน MXFP8 ดีกว่า NVFP4 ทั้งสองรูปแบบ input

## สภาพแวดล้อมและวิธีทดสอบ

- Apple M4, unified memory 24 GiB, macOS 26.6.1; ทำงานบนเครื่องเดียวกัน ไม่ใช่ห้องทดสอบที่แยกโหลดทั้งหมด
- Ollama ที่ใช้งานจริง 0.32.3 ไม่สามารถ pull Gemma สองรุ่นนี้ได้: HTTP 412 และ registry ระบุ `requires: 0.36.0`
- ใช้ Ollama 0.40.0 แบบแยกที่ `127.0.0.1:11436` พร้อม model cache แยก และใช้ runtime นี้กับทั้งสามโมเดล ไม่ได้อัปเกรดหรือเปลี่ยน embedding ของมินิคุงจริง
- Qwen ใช้ GGUF Q8_0/llama.cpp/Metal; Gemma ทั้งสองใช้ MLX engine ของ Ollama บน GPU โดย `270m` เป็น NVFP4 และอีก tag เป็น MXFP8 คนละ manifest
- เอกสารจำลองที่ระบุคำตอบด้วยมือ 24 รายการ, คำถาม 48 ข้อ: ภาษาไทยเปลี่ยนคำ 24 ข้อ และอังกฤษค้นเอกสารไทย 24 ข้อ ทุกข้อมีเอกสารเป้าหมายเดียว
- ทดสอบ cosine retrieval ล้วน เรียงจากคะแนนมากไปน้อย ไม่มี lexical blend, reranker, owner/temporal filtering หรือการสร้างคำตอบของ LLM จึงไม่ใช่ผลทดสอบ RAG ทั้งระบบ
- รูปแบบ raw-query: query ตรง ๆ และเอกสาร `title: content` เพื่อเทียบแนวทางความจำปัจจุบัน เป็นการจำลองรูปแบบ ไม่ได้เรียกผ่าน JDBC/ChatService
- รูปแบบ recommended: Qwen query `Instruct: Retrieve relevant passages that answer the query.\nQuery: ...`; Gemma query `task: search result | query: ...` และ document `title: ... | text: ...`
- query ทุกข้อเรียกเดี่ยวซ้ำ 3 ครั้งต่อรูปแบบ หลัง warm-up รวม 864 timed query requests; เวลารวม HTTP และ inference แต่ไม่รวม cosine ranking ใน Python
- เวกเตอร์เต็ม: Qwen 1,024 มิติ; Gemma 768 มิติ ไม่ truncate output และกำหนด runtime context 8,192
- ทดสอบเวลาสร้างเวกเตอร์จากข้อมูลจริง snapshot แบบ read-only 323 รายการ: ความจำปัจจุบัน 287, เอกสาร 1, acquired claims ที่เผยแพร่และยังไม่หมดอายุ 35; batch 16 ใช้ recommended formatting ไม่นำเนื้อหาส่วนตัวลงรายงาน
- ทุก response ที่ใช้วัดผลผ่านการตรวจจำนวนเวกเตอร์ มิติ finite และ nonzero ไม่มี API error ที่บันทึกในรอบนี้

## ผลเมื่อใช้ prefix ที่แนะนำ

Hit@1 คือเอกสารเป้าหมายอยู่อันดับแรก; Hit@5 คืออยู่ในห้าอันดับแรก; MRR คือค่าเฉลี่ยส่วนกลับอันดับของเอกสารเป้าหมาย

| โมเดล | Hit@1 | Hit@5 | MRR | query p50 / p95 | index ข้อมูลจริง 323 รายการ |
|---|---:|---:|---:|---:|---:|
| `qwen3-embedding:0.6b` | 47/48 (97.9%) | 48/48 | 0.9896 | 21.9 / 33.5 ms | 11.16 s |
| `embeddinggemma-2:270m` | 44/48 (91.7%) | 48/48 | 0.9583 | 18.0 / 23.4 ms | 4.69 s |
| `embeddinggemma-2:270m-mxfp8-text` | 46/48 (95.8%) | 48/48 | 0.9792 | 16.2 / 19.1 ms | 4.13 s |

## ผลเมื่อใช้ raw query

| โมเดล | Hit@1 | ไทย /24 | อังกฤษค้นไทย /24 | query p50 / p95 |
|---|---:|---:|---:|---:|
| `qwen3-embedding:0.6b` | 46/48 | 23/24 | 23/24 | 22.3 / 24.3 ms |
| `embeddinggemma-2:270m` | 45/48 | 22/24 | 23/24 | 15.2 / 19.9 ms |
| `embeddinggemma-2:270m-mxfp8-text` | 47/48 | 23/24 | 24/24 | 15.7 / 18.0 ms |

การใส่ prefix ตามคู่มือไม่ทำให้คะแนนดีขึ้นเสมอในชุดเล็กนี้: Qwen ดีขึ้น 1 ข้อ, NVFP4 ลดลง 1 ข้อ, MXFP8 ลดลง 1 ข้อ ยังไม่ควรสรุปว่าต้องตัด prefix ออกจากงานจริง หรือว่าความต่าง 1–3 ข้อมีนัยสำคัญทางสถิติ

## ขนาดและเวลาโหลด

| โมเดล | ไฟล์ดาวน์โหลด | allocation ที่ Ollama รายงานหลัง index | request แรกหลัง unload / load duration |
|---|---:|---:|---:|
| `qwen3-embedding:0.6b` | 639.2 MB | 2724.8 MiB | 627.4 / 533.4 ms |
| `embeddinggemma-2:270m` | 378.3 MB | 330.0 MiB | 713.0 / 611.6 ms |
| `embeddinggemma-2:270m-mxfp8-text` | 442.2 MB | 391.0 MiB | 674.9 / 608.8 ms |

Allocation เป็นค่าจาก `/api/ps` ไม่ใช่ peak process RSS หรือ RAM รวมของระบบ โดย runtime และวิธีจัดสรรของ GGUF/MLX ต่างกัน เวลาคำขอแรกใช้ filesystem cache ที่อาจอุ่นอยู่แล้ว ไม่ใช่การเปิดเครื่องใหม่ และขนาดไฟล์ไม่ใช่ขนาด RAM

## เกณฑ์คะแนนเดิมไม่ควรย้ายมาใช้ตรง ๆ

ใช้คำถามนอกโดเมน 8 ข้อ เช่น ดาราศาสตร์ ซาวร์โดว์ กล้วยไม้ กีตาร์ และหมากรุก เทียบกับเอกสารจำลองชุดเดิมใน recommended mode สมมติ lexical evidence เป็นศูนย์ ใช้สูตร personal knowledge เดิม `0.85 * (cosine + 1) / 2` และ minimum score 0.65

| โมเดล | คำถามนอกเรื่องที่ผ่าน 0.65 | ช่วงคะแนนนอกเรื่อง |
|---|---:|---:|
| `qwen3-embedding:0.6b` | 0/8 | 0.5244–0.5764 |
| `embeddinggemma-2:270m` | 8/8 | 0.6716–0.7068 |
| `embeddinggemma-2:270m-mxfp8-text` | 8/8 | 0.6737–0.6932 |

นี่เป็น threshold probe ไม่ใช่หลักฐานว่ามินิคุงตอบผิด 8/8 ครั้ง เพราะไม่ได้รัน context selection และ chat generation จริง แต่ยืนยันว่าระดับ similarity ของ Gemma ต่างจาก Qwen และไม่ควรตีความคะแนนสูงว่าเกี่ยวข้องเสมอ

สำหรับ MXFP8 เกณฑ์ 0.70 แยกเอกสารเป้าหมาย 48 ข้อกับ negative probes 8 ข้อได้ในชุดนี้ ภายใต้สมมติฐาน lexical=0; NVFP4 ที่ 0.72 เก็บเป้าหมายได้ 47/48 และกัน negative ได้ 8/8 ค่านี้หาโดยดูชุดทดสอบเดียวกัน จึงเป็นเพียงค่าเริ่มทดลอง ต้องยืนยันด้วยชุดใหม่ก่อนใช้จริง และต้องปรับ acquired-knowledge threshold แยกต่างหาก

## ข้อเสนอสำหรับมินิคุง

1. ถ้าจะทดลองต่อ เลือก MXFP8: เพิ่มไฟล์จาก NVFP4 ประมาณ 64 MB แต่ได้คะแนนอันดับแรกดีขึ้นทั้ง raw-query และ recommended; เวลาสร้างเวกเตอร์ข้อมูลจริงรอบนี้ 4.13 s เทียบ NVFP4 4.69 s และ Qwen 11.16 s
2. หากให้ความสำคัญกับอันดับแรกและไม่ต้องการอัปเกรด runtime Qwen ยังเป็นตัวเลือกที่ดี: recommended ได้ 47/48 เทียบ MXFP8 46/48; ทุกตัว Hit@5 เท่ากัน 48/48
3. เพิ่มคำถามภาษาไทยจากงานจริงพร้อมรายการที่ควรค้นเจอ และ negative queries อีกชุดก่อนตัดสินใจย้าย; ชุดนี้มีเอกสารเพียง 24 รายการและไม่วัด ranking ของ corpus จริง 323 รายการ
4. หากย้าย ต้องรองรับ prefix ให้ตรงโมเดล, สร้างเวกเตอร์ใหม่ครบทุกแหล่ง, ปรับ threshold, และทดสอบ Minikun retrieval end to end ก่อนเปลี่ยนค่าระบบจริง

## รันทดสอบซ้ำ

สคริปต์ใช้ Python standard library เท่านั้น และ runtime Ollama บน macOS; ดาวน์โหลดโมเดลไว้ก่อนเริ่ม

```sh
python3 tools/eval-embeddings.py --self-test
python3 tools/eval-embeddings.py --url http://127.0.0.1:11436
```

เพิ่ม `--workload target/embedding-benchmark/private-workload.json` เพื่อใช้ snapshot ข้อมูลจริงที่เก็บในเครื่องด้วยสิทธิ์ไฟล์ 0600; ไม่เก็บ snapshot ใน Git ผลรายละเอียดรวมอันดับรายคำถามและเวลาทั้งสามครั้งอยู่ที่ `target/embedding-benchmark/results.json`

## Model manifests ที่ทดสอบ

- `qwen3-embedding:0.6b`: `ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d`
- `embeddinggemma-2:270m`: `9e1df58d197e80e5a6c12110efb67b38ba6c8e23790a5044e1cafe6bea34b8f4`
- `embeddinggemma-2:270m-mxfp8-text`: `47c561d1a77f8f4ec4f855a7704db5a893e0c51c368cec31eddeae758943b6f6`

อ้างอิงสเปกและ prefix: [Google model card](https://huggingface.co/google/embeddinggemma-2), [Ollama NVFP4 tag](https://ollama.com/library/embeddinggemma-2:270m), [Ollama MXFP8 tag](https://ollama.com/library/embeddinggemma-2:270m-mxfp8-text)
