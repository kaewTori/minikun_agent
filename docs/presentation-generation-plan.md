# แผนเพิ่มความสามารถสร้างสไลด์ให้มินิคุง

วันที่: 3 ตุลาคม 2026  
สถานะ: เพิ่ม runtime สร้าง ดาวน์โหลด และแก้สไลด์ต่อแล้ว; ตรวจ deck ไทย/อังกฤษอย่างละ 8 หน้าใน LibreOffice สำเร็จ ส่วนหน้า preview ก่อนดาวน์โหลดเลื่อนไว้

## ผลลัพธ์ที่ต้องการ

ผู้ใช้พิมพ์ เช่น “ทำสไลด์แนะนำ homelab ของเรา 8 หน้า ภาษาไทย สำหรับเพื่อนที่ไม่ใช่สายไอที” แล้วมินิคุงสร้างไฟล์ PowerPoint `.pptx` ที่แก้ไขข้อความได้ พร้อมปุ่มดาวน์โหลดในแชต ใช้ข้อมูลจากข้อความ บทสนทนา เอกสารข้อความที่แนบ หรือ Personal Knowledge ตามสิทธิ์เดิม

หากไม่ได้ระบุรายละเอียด ให้เริ่มที่ 8 สไลด์รวมปก อัตราส่วน 16:9 ภาษาตามคำขอ และธีมเรียบอ่านง่าย ถามเพิ่มเติมเฉพาะข้อมูลที่ขาดแล้วทำงานต่อไม่ได้ เช่น ไม่มีทั้งหัวข้อและเนื้อหา ไม่ต้องให้ผู้ใช้อนุมัติโครงเรื่องทุกครั้งเมื่อสั่งสร้างสไลด์แล้ว

จำนวนหน้าเป็นข้อกำหนด: “8 หน้า” หมายถึง 8 หน้ารวมปก ห้ามเพิ่มหน้าปกจนกลายเป็น 9 หน้า

## สิ่งที่มีแล้วและช่องว่าง

ตรวจจาก working tree ปัจจุบัน ซึ่งมีงานอื่นกำลังแก้ไขอยู่:

| ส่วน | ใช้สิ่งที่มีแล้ว | สิ่งที่ต้องเพิ่มหรือแก้ |
| --- | --- | --- |
| รับคำขอ | `TurnPlanner`, `ToolRuntimeIntentDetector`, `ChatCapabilityFactory` | แยกคำสั่งสร้างสไลด์ออกจากคำถามทั่วไปหรือคำขอวางแผน |
| เรียกเครื่องมือ | `Tool`, `DefaultToolRegistry`, `SpringAiToolCallingRuntime` | เครื่องมือ `presentation.create` และคำแนะนำให้โมเดลจัดเนื้อหา |
| ข้อมูลอ้างอิง | Chat context, Personal Knowledge, Web Search, Browser, Deep Research | นำแหล่งข้อมูลที่ใช้จริงไปผูกกับแต่ละสไลด์ |
| งานใช้เวลานาน | `BackgroundChatService`, agent execution และ PostgreSQL เดิม | ใช้คิวเดิมและตรวจการสร้างซ้ำเมื่อ retry/restart |
| รูปประกอบ | `image.generate` และ local generated images | renderer รับเฉพาะ generated image ID ในเครื่อง; ข้ามรูปที่อ่านไม่ได้พร้อม warning |
| ไฟล์ขาออก | `ChatAttachment`, `OpenAiChatResponseFactory` | รองรับไฟล์ presentation; ปัจจุบัน SSE แปลง attachment ทุกชนิดเป็น `image_url` |
| หน้าแชต | `cockpit.js`, `cockpit-core.js`, chat sync | เก็บชนิดไฟล์และแสดงลิงก์ดาวน์โหลด; ปัจจุบัน `normalizeVisual` และ gallery มองทุก attachment เป็นภาพ |
| การอ่านเอกสาร | `KnowledgeDocumentReader` อ่าน UTF-8 ตาม allowlist | PDF, DOCX และ PPTX ขาเข้ายังไม่รองรับ ไม่รวมในรุ่นแรก |
| สร้าง PPTX | ยังไม่มี dependency ใน `pom.xml` | เพิ่ม renderer และ library สำหรับรูปแบบ PowerPoint |

## แนวทางที่เลือก

ใช้ **Apache POI XSLF (`poi-ooxml`) ใน Java** สำหรับ runtime ของมินิคุง โมเดลจัดเนื้อหาเป็นข้อมูล แล้ว renderer สร้างไฟล์ตาม layout ที่กำหนด วิธีนี้เข้ากับ Spring Boot เดิมและไม่ต้องเพิ่ม Node/Python service หรือควบคุม PowerPoint ผ่าน UI

เอกสารทางการแสดงการสร้าง `.pptx`, กำหนดขนาดหน้า ใส่ภาพ และจัดรูปแบบข้อความใน [XSLF Cookbook](https://poi.apache.org/components/slideshow/xslf-cookbook.html) ส่วน speaker notes มี API ใน [XMLSlideShow](https://poi.apache.org/apidocs/dev/org/apache/poi/xslf/usermodel/XMLSlideShow.html) ความเข้ากันได้ของเวอร์ชันที่เลือกกับ JDK 25 และการแสดงภาษาไทยต้องพิสูจน์ในงานแรก ไม่ถือว่าผ่านเพียงเพราะเขียนไฟล์ได้

ไม่มีมาตรฐานไลบรารี Java เดิมใน repo ที่สร้าง PPTX ได้ จึงเพิ่ม dependency นี้เพียงชุดเดียว ไม่เขียน OOXML เองและไม่สร้าง interface สำหรับ renderer ที่มี implementation เดียว

ลำดับการทำงาน:

```text
คำขอ + บริบทที่ผู้ใช้อนุญาต
  → TurnPlanner เลือกงานสร้างสไลด์
  → โมเดลจัดโครงเรื่องและเนื้อหา พร้อมแหล่งอ้างอิง
  → presentation.create รับและตรวจ DeckSpec
  → PresentationService สร้าง PPTX
  → บันทึกไฟล์และ spec สำเร็จแบบ atomic
  → tool result ที่ตรวจแล้ว → ChatAttachment
  → JSON / SSE / background response
  → Cockpit แสดงปุ่มดาวน์โหลด
```

รุ่นแรกใช้ layout คงที่จำนวนเล็กน้อย: ปก หัวข้อพร้อมเนื้อหา และเปรียบเทียบสองฝั่ง ข้อความเป็น native text boxes ที่แก้ได้ หลีกเลี่ยงการแปลงทั้งหน้าเป็นรูป ใช้หัวข้อและเนื้อหาสั้นพร้อม speaker notes สำหรับรายละเอียด

## สัญญาข้อมูลรุ่นแรก

เพิ่ม `PresentationSpec` เป็น Java records สำหรับชื่อ ภาษา และรายการสไลด์ แต่ละหน้ามี `layout`, `title`, `body`, `notes`, `sources` และเนื้อหาฝั่งซ้าย/ขวาเฉพาะ layout เปรียบเทียบ

ใช้ tool parameter **`spec_json` ชนิด STRING** แล้ว parse ด้วย Jackson ที่มีแล้ว เนื่องจาก `ToolParameterType`, `DefaultToolExecutor` และ `SpringAiToolCallback` ปัจจุบันรองรับแค่ primitive ไม่ขยายระบบ schema ของทุก tool เพื่อเครื่องมือเดียว ต้องทดสอบว่าโมเดลที่ใช้งานจริงส่ง JSON ซ้อนใน string ได้อย่างน่าเชื่อถือ หากไม่ผ่าน จึงค่อยเพิ่ม object schema ด้วยเหตุผลจากการทดลอง

ผลสำเร็จของ tool มี `artifact_id`, `url`, `title`, `filename`, `content_type`, `bytes`, `slide_count`, `warnings` โดยสร้างจากไฟล์ที่บันทึกจริง ห้ามใช้ URL ที่โมเดลเขียนขึ้นเองเป็นหลักฐานว่าสร้างสำเร็จ

ไฟล์แนบใช้ `type: "presentation"`, `origin: "generated"` และ metadata สำหรับชื่อไฟล์ MIME ขนาด จำนวนหน้า และ artifact ID เพิ่มแบบ optional เพื่อรักษา compatibility ของภาพเดิม

ค่าเริ่มต้นที่เสนอสำหรับรุ่นแรก: ไม่เกิน 20 หน้า, spec ไม่เกิน 128 KiB และ PPTX ไม่เกิน 20 MiB ตัวเลขเหล่านี้เป็นขีดจำกัดที่ต้องปรับจากการทดลอง ไม่ใช่ผล benchmark

## ลำดับพัฒนา

### งาน 1 — พิสูจน์การสร้าง PowerPoint

สถานะ: ผ่าน targeted test และ visual QA ด้วย LibreOffice บน host; กำหนด Sarabun สำหรับ Latin และอักษรไทย แต่ไม่ได้ฝังฟอนต์ในไฟล์

- เลือกและ pin เวอร์ชัน POI ที่ดูแลอยู่ ตรวจ dependency และลองบน JDK 25
- ทดลองสร้าง 3 หน้าที่มีไทย อังกฤษ ตัวเลข bullet และ speaker notes
- ใช้ฟอนต์ไทย เช่น Noto Sans Thai โดยตรวจการติดตั้งบน host และเครื่องที่เปิดไฟล์ การกำหนดชื่อฟอนต์ใน PPTX ไม่ได้ทำให้ฟอนต์ถูกฝังอัตโนมัติ
- เปิดใน PowerPoint และ/หรือ LibreOffice ที่มีจริง ตรวจข้อความล้น สระ/วรรณยุกต์ และการแก้ข้อความได้ เก็บรายการโปรแกรมที่ตรวจจริง

ผ่านเมื่อไฟล์เปิดได้ ไม่มี repair warning ภาษาไทยอ่านถูก และข้อความแก้ได้ หากไม่มีโปรแกรมสำหรับตรวจหน้าตา ให้ระบุว่า visual QA ยังไม่ผ่านและอย่ารับรองจากการอ่าน XML อย่างเดียว

### งาน 2 — ทำรุ่นแรกให้ครบตั้งแต่แชตถึงดาวน์โหลด

สถานะ: ต่อเส้นทาง native tool, JSON/SSE/background, attachment, sync และ owner-scoped download แล้ว

เพิ่ม package `com.minikun.presentation` โดยเริ่มจาก:

- `PresentationSpec`: สัญญาข้อมูลและ validation
- `PresentationService`: render, จัดเก็บ spec/metadata และไฟล์ใน directory ที่กำหนด
- `PresentationTool`: implement `Tool` ชื่อ `presentation.create`
- `PresentationController`: ดาวน์โหลดผ่าน `/v1/presentations/{artifactId}/download`
- `PresentationConfiguration`: bean และค่าที่จำเป็น เช่น เปิด/ปิดความสามารถกับ storage directory

รวมส่วน renderer/storage ใน service เดียวก่อน แยกเมื่อมีเหตุผลจากขนาดหรือความซับซ้อนจริง ใช้รูปแบบไฟล์ local เดิมเป็นตัวอย่าง แต่ไม่ยัด PPTX เข้า `GeneratedImageStore` ซึ่งตรวจเฉพาะภาพ

แก้จุดเชื่อมต่อเดิมที่จำเป็น:

1. `ToolRuntimeIntentDetector` / `TurnPlanner`: จับ “ทำสไลด์”, “สร้าง PowerPoint”, “create a deck” และคำขอต่อเนื่องที่ชัดเจน แต่ “ช่วยวางแผนระบบทำสไลด์” หรือ “PowerPoint คืออะไร” ต้องไม่สร้างไฟล์
2. `ChatCapabilityFactory`: สอนให้จัดเนื้อหาและเรียก tool เมื่อมีความสามารถนี้จริง; provider ที่ไม่รองรับ tool calling ต้องแจ้งข้อจำกัด ไม่อ้างว่ามีไฟล์แล้ว
3. `SpringAiToolCallingRuntime`: เก็บ artifact result ที่สำเร็จจาก tool execution ในผลลัพธ์ของ request ก่อนส่งให้โมเดลเขียนคำตอบ แล้วส่ง metadata ผ่าน gateway ถึง `ChatService` หากมี reasoning/review ต่อ ต้องรักษา metadata นี้ไว้ด้วย
4. `ChatService` / `OpenAiChatResponseFactory`: แนบไฟล์ที่ยืนยันแล้วทั้ง JSON และ SSE; `imageDeltas()` ต้องเลือกเฉพาะภาพ ไฟล์ PPTX อยู่ใน `attachments` และไม่เข้า `delta.images`
5. `BackgroundChatService`: ใช้ job เดิม รอจนไฟล์พร้อมจึงจบงาน ไม่ใช้สถานะ `image_status` แทนสถานะสไลด์ และไม่สร้างคิว presentation อีกชุด
6. `cockpit.js`: ตรวจชนิด attachment ก่อน normalize/render; แสดงชื่อไฟล์ จำนวนหน้า และปุ่มดาวน์โหลดที่กดด้วยคีย์บอร์ดได้
7. `cockpit-core.js` / sync: รักษาชนิดและ metadata ของ presentation หลัง reload หรือเปลี่ยนอุปกรณ์ ไม่ส่งไฟล์ PPTX กลับไปเป็น vision input

ตรวจ JSON ก่อน render: ชนิดข้อมูล layout ที่รองรับ จำนวนหน้า ความยาว title/body และ sources ห้ามปล่อยโมเดลกำหนดพิกัด arbitrary หรือส่ง code ให้รัน ถ้าข้อความเกินพื้นที่ให้ตอบข้อผิดพลาดเพื่อจัดเนื้อหาใหม่ ไม่ตัดสาระเงียบ ๆ หรือย่อฟอนต์จนอ่านไม่ได้

เก็บ spec ไว้กับ artifact เพื่อให้ตรวจย้อนหลังและต่อยอดแก้ไขได้ เก็บเพียง reference/metadata ในแชต ไม่เก็บ binary หรือ Base64 ใน conversation history

ผ่านเมื่อคำขอภาษาไทยและอังกฤษสร้างไฟล์ตามจำนวนหน้าที่สั่ง ดาวน์โหลดได้จากแชต และไฟล์แนบยังอยู่หลัง reload ทั้งแบบ JSON, SSE และ background job

### งาน 3 — เพิ่มภาพและตรวจคุณภาพหน้าตา

สถานะ: รองรับภาพที่มินิคุงสร้างผ่าน image tool พร้อม layout แบบ split/editorial; in-app preview UI ยังเลื่อนไว้

- ใช้ภาพที่ผู้ใช้มีหรือที่มินิคุงสร้างผ่าน pipeline เดิมก่อน; image generation เป็นตัวเลือก และทำทีละภาพตามขีดจำกัด GPU เดิม
- อ้างภาพด้วย ID ที่ตรวจสิทธิ์แล้ว ไม่รับ arbitrary filesystem path/URL จากโมเดล หากจำเป็นต้องดึง remote image ให้ผ่าน URL policy และ byte/type limits เดิม
- เพิ่ม layout ภาพพร้อมข้อความ และหน้า preview เพื่อดูสไลด์ก่อนดาวน์โหลด เมื่อมี renderer ที่ผ่านการตรวจภาษาไทยแล้ว
- ตรวจ bounding boxes และ render ดูทุกหน้าเพื่อหาข้อความล้น ภาพผิดสัดส่วน และองค์ประกอบทับกัน การเช็กไฟล์เปิดได้ไม่แทน visual QA
- ใส่ที่มาของข้อเท็จจริงและเครดิตภาพใน speaker notes; ข้อจำกัดที่มีผลต่อข้อสรุปต้องมองเห็นบนสไลด์
- ภาพสร้างไม่สำเร็จให้ส่งงานที่ยังอ่านได้พร้อม warning ถ้าตัว PPTX สร้างไม่สำเร็จต้องแจ้งล้มเหลวและไม่แสดงปุ่มดาวน์โหลด

ผ่านเมื่อภาพไม่ทำให้จำนวนหน้าผิด ภาษาไทยยังอ่านถูก แหล่งข้อมูลตามกลับได้ และกรณีภาพล้มเหลวไม่ทำให้งานทั้งชิ้นหาย

### งาน 4 — แก้สไลด์ต่อจากบทสนทนา

สถานะ: เพิ่ม `presentation.read_latest` และ `presentation.revise`; revision สร้าง artifact ใหม่โดยไม่เขียนทับฉบับก่อน

- เพิ่ม `presentation.revise` หลัง create ทำงานครบแล้ว โดยอ่าน artifact/spec เดิมจาก owner และ conversation ที่อนุญาต
- รองรับคำสั่ง เช่น “ย่อหน้า 3” หรือ “เปลี่ยนกลุ่มผู้ฟังเป็นนักเรียน” และให้โมเดลส่ง spec ฉบับใหม่
- สร้าง artifact ใหม่พร้อม reference ถึงรุ่นก่อน ไม่เขียนทับไฟล์เดิม เพื่อกลับไปดาวน์โหลดงานก่อนหน้าได้
- ไม่เริ่มจาก patch/diff engine; regenerate จาก spec สำหรับ deck ขนาดไม่เกิน 20 หน้าก่อน

ผ่านเมื่อคำขอต่อเนื่องเลือก deck ถูก แก้เฉพาะสาระที่สั่ง จำนวนหน้ายังตรง และไฟล์เดิมไม่เสียหาย

## ความถูกต้องและการจัดเก็บที่ต้องมีในรุ่นแรก

- แหล่งข้อมูลส่วนตัวใช้สิทธิ์เดิม ไม่ค้นเอกสารของ owner อื่น ห้ามสร้างตัวเลขหรือแหล่งอ้างอิงเพื่อเติมสไลด์
- Endpoint ดาวน์โหลดต้องใช้กลไกยืนยันตัวตน/management token ที่เหมาะกับ deployment แล้วตรวจ owner กับ artifact ฝั่ง server; UUID หรือ `owner_id` ที่ผู้เรียกส่งมาอย่างเดียวไม่ใช่ authorization
- ใช้ artifact ID เป็นชื่อไฟล์ภายใน ตรวจ canonical path และไม่ตาม symlink ออกนอก storage directory
- เขียนลงไฟล์ชั่วคราวก่อน แล้ว publish ไฟล์และ metadata ที่ครบ ห้ามแสดงไฟล์ครึ่งหนึ่งเป็นงานสำเร็จ ส่ง MIME สำหรับ PPTX, `Content-Disposition: attachment` และ `nosniff`
- จำกัดเวลาและขนาด รักษา interrupt/cancel และล้างไฟล์ชั่วคราวเมื่อพลาด
- การสร้างในเครื่องตามคำขอไม่ต้องถามยืนยันซ้ำ แต่ต้องทำ idempotency: owner + background operation/request ID + hash ของ spec ใช้ผลที่เสร็จแล้วซ้ำเมื่อ retry/restart แม้ tool call ID เปลี่ยน ไม่ใช้ call ID เพียงอย่างเดียว
- กำหนด retention เริ่มต้นที่เสนอ 30 วันและล้างเฉพาะ generated artifacts ใน root นี้; ไฟล์หมดอายุต้องมีข้อความให้สร้างใหม่ การล้างไม่ตาม path ที่มาจากโมเดล

## การตรวจรับ

เพิ่ม checks ใน JUnit และ Node test harness ที่ repo มีอยู่ ไม่เพิ่ม test framework:

- สร้าง/อ่าน PPTX กลับ ตรวจจำนวนหน้า ข้อความไทย notes และ title/body ที่ยังเป็น native text
- Reject spec เสีย layout ไม่รองรับ จำนวนหน้า/ขนาดเกิน และการเข้าถึง artifact ของ owner อื่น
- ผลสำเร็จจาก tool เท่านั้นที่ทำให้มีไฟล์แนบ; URL ใน prose ของโมเดลไม่ใช่ artifact
- Presentation ไม่เข้า `delta.images`, image gallery หรือ follow-up vision payload และไม่หายหลัง chat sync/reload
- คำขอสร้างสไลด์เข้า tool path แต่คำขอวางแผนหรืออธิบายไม่สร้างงาน; ทดสอบโมเดล/provider ที่ไม่รองรับ tools
- Timeout/retry/restart ไม่ทิ้งไฟล์ครึ่งหนึ่ง ไม่ทำ deck ซ้ำ และ cancellation ไม่กลายเป็น success
- ตรวจจริงอย่างน้อย deck ไทย 8 หน้าและ deck อังกฤษ 8 หน้าในโปรแกรมเปิด PPTX ที่ระบุไว้ การทำ screenshot/render QA เป็นงานตรวจรับ ไม่ถือว่า unit test ครอบคลุมหน้าตาแล้ว

รัน targeted checks ของ presentation, response factory, tool runtime, turn planner และ cockpit transport/sync ก่อน แล้วรันชุดตรวจที่เกี่ยวข้องตามสถานะ working tree ณ ตอนลงมือทำ

## สิ่งที่เลื่อนไปก่อน

PDF export, รับ PDF/DOCX/PPTX เข้ามาสรุป, นำเข้า template ของผู้ใช้, native charts/tables ซับซ้อน, animation และแก้ deck ภายนอก เพิ่มเมื่อมีคำขอใช้งานจริง

ไม่เพิ่ม Google Slides/cloud upload, vector database, agent ใหม่ หรือ UI editor สำหรับลากจัดหน้าในรุ่นแรก; เก็บ JSON metadata ในเครื่องคู่กับ PPTX สำหรับ owner check, retry และ revision ผู้ใช้แก้รายละเอียดใน PowerPoint ได้จากไฟล์ที่ส่งออก

**งานที่เลื่อนไว้:** preview ใน Cockpit ก่อนดาวน์โหลด เพิ่มเมื่อมีความต้องการดูภาพทุกหน้าก่อนรับไฟล์; ตอนนี้ดาวน์โหลดไปตรวจหรือแก้ต่อใน PowerPoint/LibreOffice ได้
