# Investment Copilot Runtime

Investment Copilot เฟสแรกเป็นระบบช่วยบันทึกและวิเคราะห์การลงทุนแบบ local และ owner-scoped
โดยตั้งใจแยกการคำนวณตัวเลขออกจาก LLM อย่างชัดเจน โมเดลมีหน้าที่เลือก tool และอธิบายผล
ส่วนยอดถือครอง ต้นทุน กำไรที่รับรู้ และ policy breach คำนวณใน Java ด้วย `BigDecimal`

## ขอบเขต

รองรับ:

- investment policy: สกุลเงินฐาน benchmark และเพดานสัดส่วนต่อหลักทรัพย์
- investment mandate: เป้าหมาย ระยะเวลา และ risk tolerance ของเจ้าของ
- immutable transaction ledger: `BUY`, `SELL`, `DIVIDEND`, `FEE`, `CASH_DEPOSIT`, `CASH_WITHDRAWAL`
- ยกเลิกรายการผิดด้วย `void_transaction` โดยเก็บรายการเดิมไว้เพื่อ audit
- average-cost position และ realized profit/loss
- cost-basis allocation และคำเตือนเมื่อเกิน policy
- investment thesis, invalidation condition และรอบทบทวน
- hypothetical buy simulation แบบไม่บันทึกและไม่ส่งคำสั่ง
- external market/FX/SEC reads และ Alpaca Paper workflow แบบแยกจาก ledger
- daily investment companion: เก็บข่าวที่จับคู่กับสินทรัพย์ในพอร์ต/active thesis พร้อม source, เวลา และระดับ materiality
- QuantDinger sidecar แบบเลือกเปิดใช้สำหรับ market data, strategy artifact/version และ asynchronous backtest

ข้อจำกัดที่ยังคงมี:

- ledger หลายสกุลเงิน (แปลงงบด้วย FX ได้ แต่ transaction ยังต้องตรงสกุลเงินฐาน)
- corporate actions เช่น split, spin-off หรือ merger
- tax-lot accounting
- การส่งคำสั่งเข้า broker จริง (รองรับเฉพาะ Alpaca Paper ที่ยืนยันซ้ำ)

ผลจาก `investment.analyze` ยังคงระบุ valuation basis ว่าเป็น average cost เสมอ
ส่วน `investment.data` ใช้ latest quote แบบ read-on-demand และต้องรายงานแหล่งที่มา/เวลาเสมอ

## Tools

อัปเดต 8 ต.ค. 2026: คำสั่ง `อัพเดท port`/`อัปเดตพอร์ต` ที่รายงานการซื้อย้อนหลัง
ต้องเข้าทาง ledger ก่อนเครื่องมืออ่านราคา แม้ข้อความมีคำว่า `ราคา` และชื่อ ticker
รองรับหลายบรรทัด `ซื้อ SCHD 29.37$ ในราคา 32.80$ ต่อหน่วยได้ 0.8939024 หน่วย`
ผ่าน batch proposal เดียว โดยไม่เรียก broker และบันทึกเมื่อเจ้าของยืนยันเท่านั้น
ยอดซื้อถูกตีความเป็นยอดจ่ายรวม; ส่วนต่างกับจำนวน×ราคาแสดงเป็นค่าธรรมเนียมใน proposal
พร้อมขอให้ตรวจสอบว่ามาจากค่าธรรมเนียมจริงหรือราคาที่ปัดเศษก่อนยืนยัน
จำนวน สกุลเงิน หรือยอดที่ขัดกันจะปฏิเสธทั้ง proposal ไม่บันทึกเฉพาะบรรทัดที่อ่านได้
รายการซ้ำในวันเดียวกันตรวจด้วย fingerprint เดิม; fingerprint รายการขายเดิมยังใช้ได้
ผลอ่านราคาที่มี `Instant` ใช้ ObjectMapper ที่รองรับ Java time เพื่อไม่รายงาน format error
นำตัวแก้ไขนี้ขึ้น runtime ที่รันอยู่แล้ว 8 ต.ค. 2026: Maven ตรวจ 1,384 รายการ ไม่มี failure/error
(ข้าม 13 รายการตามเงื่อนไขทดสอบ), artifact ที่ติดตั้งตรงกับไฟล์ build และ health/db/conversation persistence เป็น UP

### `investment.manage`

คำสั่งอ่านไม่เปลี่ยน state และทำได้ทันที:

- `get_policy`
- `list_transactions`
- `list_theses`

คำสั่งเขียนทุกชนิดบันทึก proposal ใน durable confirmation store ก่อน:

- `set_policy`
- `set_quote_priority`
- `add_transaction`
- `void_transaction`
- `save_thesis`
- `close_thesis`

เมื่อเจ้าของตอบยืนยัน `InvestmentConfirmationRouter` จะ replay arguments เดิมจาก PostgreSQL
โดยใช้ `ownerId` เดิม ไม่อาศัยความจำหรือการตีความใหม่ของโมเดล

`set_quote_priority` รับหลาย symbol แบบคั่นด้วย comma และใช้ค่า `HIGH`, `NORMAL` หรือ `MINOR`
เพื่อกำหนดความถี่การ refresh ราคาเท่านั้น ไม่ใช่คำแนะนำให้ซื้อหรือขาย เช่น
`ตั้ง AAPL, MSFT, TSLA เป็น minor priority สำหรับการติดตามราคา`

### `investment.analyze`

- `portfolio` คืน policy, positions, total cost basis, realized profit/loss, income, fees และ warnings
- `review` รวม portfolio, active theses และ published acquired knowledge ต่อ symbol สำหรับการทบทวนการถือระยะยาว
- `simulate_buy` เพิ่ม hypothetical cost ให้ symbol แล้วตรวจ projected allocation กับ policy

Tool นี้เป็น read-only และไม่มี capability สำหรับส่ง order

คำขอภาษาธรรมชาติ เช่น `ช่วยทบทวนพอร์ตระยะยาว` จะถูก route เข้า `investment.analyze` action `review` โดยตรง
ผลลัพธ์ยังใช้ average cost และจะระบุเมื่อยังไม่มี transaction หรือ published knowledge สำหรับพอร์ตนั้น

### `investment.data`

- `quotes` ดึงราคาล่าสุดแบบ batch จาก Twelve Data เมื่อกำหนด `MINIKUN_INVESTMENT_TWELVE_DATA_API_KEY`
- `portfolio_value` คูณราคาล่าสุดกับจำนวนถือครองเพื่อแสดง market value และ unrealized P/L ของพอร์ตสกุลเดียวกัน
- `fx` ดึง reference rate รายวันจาก Frankfurter โดยไม่ต้องใช้ key; ส่ง `amount` พร้อม
  `base_currency`/`quote_currency` หรือ `pair` เพื่อรับ `converted_amount` ที่คำนวณด้วย BigDecimal
  ยอดนี้ยังไม่หัก spread และค่าธรรมเนียมของ broker
- `sec_filings` ดึงรายการ filing ล่าสุดจาก SEC EDGAR โดยใช้ ticker mapping และ submissions API
- `paper_account` และ `paper_order` ใช้ Alpaca Paper เท่านั้น; `paper_order` ต้องยืนยันอีกครั้งและไม่แก้ immutable ledger ให้เอง

การใช้งานภายนอกเป็น read-on-demand เพื่อคุม quota; หากยังไม่มี key จะแสดง `setup_required` แทนการเดาราคา

### คำแนะนำเติมพอร์ตในแชต

คำถามเช่น `เรามีอยู่ 5000 บาทเอาไปเติมอะไรดีวันนี้` และ `แนะนำเติมพอร์ตวันนี้`
ใช้ native tool loop เพื่ออ่าน ledger/mandate/thesis ผ่าน `investment.analyze review`
runtime เรียก review ก่อนส่งเข้าโมเดล และเรียก FX ให้อัตโนมัติเมื่อระบุงบหนึ่งยอดชัดเจน
เพื่อไม่ให้โมเดลถามรายการถือครองซ้ำหรือสมมติค่าเงินเอง; งบหลายยอดไม่ถูกนำมารวมโดยเดา
แล้วให้โมเดลเสนอหนึ่งแผนหลักพร้อมยอด เหตุผลเรื่อง concentration/diversification และความเสี่ยง
คำถามนี้ไม่ถูกปิดด้วย `brief_text` ของ daily monitor หรือรายการถือครองอย่างเดียว
ไม่กำหนดคำตอบตายตัวว่าให้ซื้อกองทุนใด และ thesis ที่ยังไม่ครบไม่ปิดกั้นคำแนะนำแบบมีสมมติฐาน

คำถามต่อเนื่อง `เปลี่ยนใหม่เป็น 3800 บาท` ใช้งบใหม่ ส่วน `ขอยอดเป็น $ หน่อย`
ใช้แผนล่าสุดและ FX ที่มีวันที่/แหล่งข้อมูล; ทั้งประวัติที่เก็บในระบบและ messages ที่ client ส่งมารองรับเส้นทางนี้
ราคา สัดส่วนตามมูลค่าตลาด และข้อมูลกองทุนต้องมีหลักฐานปัจจุบันก่อนนำมาอ้าง
เมื่อดึงข้อมูลไม่สำเร็จ ให้อธิบายข้อจำกัดและเสนอได้เฉพาะแผนตามต้นทุนที่ระบุสมมติฐาน
คำแนะนำไม่ถือเป็นการยืนยันซื้อขายหรือการเปลี่ยน ledger/policy/thesis
เส้นทางนี้เปิดเฉพาะเครื่องมืออ่าน/วิเคราะห์/ค้นเว็บ และปฏิเสธ brokerage action ของ `investment.data`
ก่อนเรียก executor แม้โมเดลส่ง `confirmed=true`; สถานะ `not_configured` ระบุว่าไม่ได้ดึงข้อมูล
และส่งคำแนะนำให้โมเดลหยุดเรียกซ้ำจนกว่าจะตั้งค่า พร้อมใช้ข้อเท็จจริงที่มีทำข้อเสนอแบบมีเงื่อนไข

ตรวจ routing, multi-turn blocking/streaming และยอด FX โดยไม่เรียก broker:

```sh
./mvnw -q -Dtest=TurnPlannerTest,ChatServiceChatOrchestrationTest,ChatCapabilityFactoryTest,InvestmentMonitorRouterTest,InvestmentReviewRouterTest,InvestmentMarketRouterTest,InvestmentDataToolTest,InvestmentExternalDataServiceTest test
```

การทดสอบเหล่านี้ตรวจ contract ด้วยโมเดลจำลอง; คุณภาพเหตุผลและภาษาไทยของโมเดลจริง
ต้องตรวจด้วยบทสนทนาสามรอบข้างต้นหลังนำ runtime ใหม่ไปใช้

ทดลอง 7 ต.ค. 2026 ด้วยโมเดล local ค่าเริ่มต้น, temperature 0.7, พอร์ตตัวอย่าง 9 รายการ
และ FX จริงจาก Frankfurter: runtime อ่านพอร์ตและแปลงงบ 5,000 → 3,800 บาทได้ต่อเนื่อง
ยอด 3,800 บาทในรอบทดสอบเท่ากับ $112.97 ที่ rate THB/USD 0.02973 วันที่ 2026-10-07
โดยไม่เขียน ledger หรือส่ง order (ตัวเลขนี้เป็นหลักฐานรอบทดสอบ ไม่ใช่ค่า FX สำหรับคำตอบในอนาคต)
การรับข้อมูลและยอดแปลงผ่าน แต่คำตอบของโมเดลยังต้องตรวจเรื่อง policy หลังเติมเงิน
ยอดแต่ละสินทรัพย์ ความเสี่ยงหุ้นและ ETF และความกระชับ จึงยังไม่ถือว่าคุณภาพคำแนะนำเทียบเท่า ChatGPT ตัวอย่าง

ทดลองโมเดล local 12B ที่ติดตั้งอยู่ด้วยพอร์ตและข้อมูลเดียวกัน: เรียก `simulate_buy` ได้
และใช้ยอดรวม FX ถูกต้อง แต่ยังสลับน้ำหนัก QQQM กับ VTI, เสนอเงินรวมเกินงบ
และพยายามเรียก paper order ซึ่งถูก read-only guard ปฏิเสธก่อนถึง executor
ผลนี้จึงไม่ผ่านด้านคุณภาพคำแนะนำเช่นกัน การเปลี่ยนเป็นโมเดลใหญ่ขึ้นเพียงอย่างเดียวยังไม่เพียงพอ
ชุดทดสอบ 141 checks ผ่านด้าน routing, ข้อมูล, FX, owner scope และข้อจำกัดการเขียน
ขั้นต่อไปสำหรับการรับรองคำแนะนำคือให้ Java ตรวจแผนจัดสรรหลายสินทรัพย์และ policy หลังเติมเงิน
ก่อนแสดงคำตอบ พร้อมตรวจรับภาษา/เหตุผลด้วยเคสนี้ (เป็นผลก่อนตัวแก้ไขคุณภาพคำแนะนำด้านล่าง)

อัปเดตคุณภาพคำแนะนำ 8 ต.ค. 2026:

- `ลงทุนอะไรเพิ่มดี` ถูกจัดเป็นการเติมพอร์ต และเปิดเส้นทางอ่าน ledger ทั้ง blocking/streaming
- อ่านข้อมูลผู้ออกกองทุน โดยค้นตัวผู้ออกจากชื่อกองทุนแล้วตรวจหน้า issuer อีกครั้ง ไม่ใช้บทความเริ่มลงทุนเป็นฐานคำแนะนำ
- เมื่อไม่มี mandate ที่ขัดกัน และกองทุนตลาดหุ้นรวมที่ยืนยันจาก issuer ยังมีสัดส่วนต่ำกว่ากองทุนส่วนอื่น
  ใช้ข้อเสนอเสริม core แบบมีสมมติฐาน ไม่กำหนด ticker ตายตัว และไม่ใช้กับ core ที่ครองพอร์ตอยู่แล้ว
- กรณี mandate เฉพาะ ใช้แผน JSON แบบน้ำหนักสัมพัทธ์จากโมเดล และตรวจ symbol, ภาษา, source และ policy ก่อนแสดง
- `InvestmentAdvicePlan` คำนวณยอดทุกส่วนด้วย BigDecimal รวมเงินให้ตรงงบทั้งสองสกุลเงิน
  คำนวณน้ำหนักตามต้นทุนหลังเติมจริงตามสมมติฐาน รวมถึงการลดน้ำหนักของตัวที่ไม่ได้เติม และแสดง policy ที่ขัดกัน
- การขอยอดดอลลาร์แปลงแผนล่าสุดโดยตรง; รองรับวันที่ FX ทั้ง ISO และรูปแบบ array ของ Java-time serialization
- ยังคงเป็น read-only ไม่เปลี่ยน ledger, mandate, thesis หรือส่งคำสั่ง broker

ตรวจรับกับข้อมูลและบริการจริงโดยไม่เขียนพอร์ต:

```sh
./mvnw -q -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/investment-advice-classpath.txt
# ใช้ credentials ที่ตั้งไว้ใน environment; ไม่ใส่ key ในคำสั่งหรือรายงาน
java -javaagent:$HOME/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.10/byte-buddy-agent-1.18.10.jar \
  -cp "target/test-classes:target/classes:$(cat target/investment-advice-classpath.txt)" \
  com.minikun.search.internal.InvestmentAdviceLiveTest --fixture
# เอา --fixture ออกเพื่อใช้ ledger จริง; connection เปิด default_transaction_read_only
```

ผ่านพอร์ตอ้างอิง `5,000 → 3,800 บาท → $` และพอร์ตจริง `1,000 → 800 บาท → $`
พร้อมตรวจ holdings/cost/policy ไม่เปลี่ยน ผลข้อความอยู่ใน `target/investment-advice-fixture.txt`
และ `target/investment-advice-default.txt`; FX และ fund evidence อ่านจากบริการจริง ไม่ใช่ค่าที่ hardcode
ขอบเขตปัจจุบันเสนอเฉพาะสินทรัพย์ที่ยืนยันอยู่ใน ledger และเงินสด ไม่รับรองผลตอบแทนหรือประเมินว่าราคาตลาดวันนี้ถูก

### `investment.monitor`

เป็นคู่หูวิเคราะห์แบบ read-only สำหรับคำถามเรื่องแผน พอร์ต ข่าว และมุมมองการลงทุน:

- `plan` อ่าน mandate, portfolio, active theses และ execution contract
- `daily_brief` อ่านรายงานล่าสุด; `refresh` ดึงข่าว/ราคาใหม่แล้วบันทึก snapshot
- `news` อ่านข่าวที่เคยเก็บไว้ตามช่วงเวลา

รายงานประจำวันจะติดตาม symbol จาก holdings และ active theses, ค้นข่าวช่วงล่าสุดผ่าน Search Runtime,
deduplicate ตาม owner + symbol + URL, จัดระดับ `HIGH`, `MEDIUM`, `LOW`, และรวม quote ล่าสุดเมื่อมี Twelve Data key
รายงานจะบอก `status=partial` กับ `setup_required` เมื่อข้อมูลไม่ครบ และแนบ URL/source/เวลาเพื่อให้ตรวจสอบย้อนกลับได้

สำหรับ Twelve Data Basic ที่มีโควตา 8 API credits ต่อนาที ระบบจะขอราคาสดไม่เกิน 8 symbols ต่อรอบ:
5 symbols ที่มี priority สูงจะ refresh ทุกวัน และอีก 3 symbols จะหมุนตามวันจากกลุ่มที่เหลือ
symbols ที่ไม่ได้ refresh ใช้ราคาจาก brief ก่อนหน้า พร้อม `quote_status=cached`, `quote_age_seconds` และ `stale_symbols`
เพื่อไม่ให้มินิคุงแสดงตัวเลขเก่าว่าเป็นราคาสด; ข่าวยังค้นหาครบทุก symbol ที่ติดตาม
เจ้าของสามารถตั้ง `MINOR` ให้ symbol ที่ไม่ต้องการ refresh ทุกวันได้ โดย symbol นั้นจะไม่ถูกเลือกในกลุ่ม fixed
และจะอยู่ใน rotation แทน; priority นี้ไม่ลดความถี่การติดตามข่าว

Brief จะบันทึกสรุปภาษาไทยที่ผ่านการตรวจไว้ใน `news.events.what_happened` และ `brief_text`
ก่อนส่ง reminder; คำถามสรุปข่าวในแชตอ่าน `brief_text` เดียวกันโดยไม่สรุปใหม่ตอนแสดงผล
โมเดลเรียบเรียงเฉพาะข้อเท็จจริง ส่วนสัดส่วนต้นทุน เหตุผลการถือและเงื่อนไขทบทวนมาจาก ledger/thesis จริง
ใช้โมเดลหลักที่ตั้งไว้เฉพาะงานสรุปข่าวลงทุน ผ่าน `MINIKUN_INVESTMENT_SUMMARY_MODEL` ซึ่ง override ได้
งาน task/memory อื่นยังใช้โมเดลเดิม
หากสรุปไม่ผ่าน จะระบุว่าเรียบเรียงไม่สำเร็จ แทนการบอกว่าข่าวไม่มีหลักฐาน

วันเผยแพร่รองรับ ISO และ RFC 1123 ของ Tavily; ข่าวที่ไม่ทราบวันเผยแพร่ ข่าวเก่าและบทความคาดเดาระยะยาว
จะไม่อยู่ใน daily brief วันเกิดเหตุการณ์แยกเป็น `event_at` เฉพาะเมื่อข้อความระบุวันที่ของเหตุการณ์ชัดเจน
เลือกไม่เกิน 3 ประเด็นจากความสำคัญ แหล่งข่าว น้ำหนักต้นทุนในพอร์ตและความใหม่ แล้วรวมข่าวซ้ำ
VTI, QQQM และ SCHD ใช้ข่าวภาพตลาดที่เกี่ยวข้องเป็นบริบท โดยไม่อ้างว่ารู้หุ้นหรือสัดส่วนทั้งหมดในกองทุน
ราคาที่ใช้รอบก่อนจะแสดงชื่อ symbol/เวลา ราคาพ้นอายุ cache จะไม่ถูกนำมาตีมูลค่า และรายงานมูลค่าบางส่วนจะระบุชัด

เมื่อยังไม่มี thesis จะขอเหตุผลการถือหนึ่งตัวก่อน เจ้าของตอบได้ด้วย
`เหตุผลที่ถือ AMZN: <เหตุผลจริง>; ทบทวนเมื่อ: <เงื่อนไขจริง>`
ระบบสร้าง proposal ผ่าน `investment.manage save_thesis` และบันทึกเมื่อเจ้าของยืนยันตามขั้นตอนเดิม

ท้าย reminder จะบอกให้เปิดแชต เติมสองช่อง แล้วตอบ `ยืนยัน` หลังมินิคุงทวนข้อมูล
แจ้งเตือนแนบ click URL ไปยัง `/cockpit/?view=chat&reply_symbol=SCHD` ผ่าน origin ของ device sync ที่ตั้งไว้
ntfy ใช้ Click และปุ่ม view ตาม [รูปแบบการส่งแจ้งเตือนของ ntfy](https://docs.ntfy.sh/publish/#action-buttons)
ส่วนเบราว์เซอร์มีปุ่ม `ตอบเรื่อง SCHD` และเมื่อกดแจ้งเตือนจะเปิดแบบตอบเดียวกัน
แบบตอบเติมเฉพาะชื่อสินทรัพย์ ไม่ส่งข้อความหรือยืนยันการบันทึกอัตโนมัติ
ถ้ามีข้อความ ไฟล์ หรือการตอบที่กำลังทำงานอยู่ จะให้เปิดแบบตอบอีกหน้าต่างแทนการทับงานเดิม

ตรวจรับด้วยพอร์ตจริง Search Runtime และโมเดลจริงโดยไม่ส่ง reminder หรือเขียน ledger:

```sh
# ต้องมี SPRING_DATASOURCE_PASSWORD และ MINIKUN_SEARCH_TAVILY_API_KEY ใน environment
./mvnw -q -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/investment-live-classpath.txt
java -cp "target/test-classes:target/classes:$(<target/investment-live-classpath.txt)" com.minikun.search.internal.InvestmentBriefLiveTest
```

ผลตัวอย่างอยู่ใน `target/investment-brief-preview.txt` และ `.json`; ราคาใน preview ใช้ snapshot เดิม
เพื่อตรวจการสรุปโดยไม่ใช้ quota ราคาเพิ่ม การทดสอบจะไม่ผ่านหากข่าวที่เลือกไม่มีวันเผยแพร่หรือไม่มีสรุปภาษาไทยที่ตรวจแล้ว
log แยก `news_candidate_rejected`, `news_summary_rejected`, `news_summary_failed` และ `news_summary_completed`
เพื่อแยกปัญหาแหล่งข้อมูลออกจากการเรียบเรียง ไม่ใช้สถานะส่งข้อความสำเร็จเป็นตัววัดคุณภาพสรุป

Scheduler จะส่ง brief หนึ่งครั้งต่อ local date หลังเวลา `08:15` Asia/Bangkok โดย default
และ mark ว่าส่งสำเร็จหลัง Notification Dispatcher รับงานแล้วเท่านั้น; การ refresh หรือส่งซ้ำไม่สร้าง order

### QuantDinger sidecar

Mini-kun ไม่ได้นำ QuantDinger ทั้ง repo เข้ามาเป็น dependency แต่ใช้ adapter ขนาดเล็กไปยัง Agent Gateway ของ
[QuantDinger](https://github.com/OpenByteInc/QuantDinger) เฉพาะความสามารถที่เหมาะกับ research workflow:

- อ่าน health/runtime/markets/price/klines
- compile strategy และอ่าน strategy versions
- บันทึก strategy source/version เมื่อเจ้าของยืนยันซ้ำ
- submit asynchronous backtest พร้อม idempotency key และอ่าน job status

Tool `investment.quantdinger` เปิดเฉพาะ R/B contract ที่กำหนดไว้และไม่มี live-order capability จาก Mini-kun
การใช้ QuantDinger ต้องเปิด flag และกำหนด agent token ที่มี scope R/B; default ปิดไว้:

```properties
minikun.investment.quantdinger.enabled=true
minikun.investment.quantdinger.url=http://127.0.0.1:8888
minikun.investment.quantdinger.agent-token=${MINIKUN_INVESTMENT_QUANTDINGER_AGENT_TOKEN:}
```

Backtest เป็นหลักฐานประกอบการตัดสินใจ ไม่ใช่การรับประกันผลตอบแทน และคำแนะนำของมินิคุงเป็น conditional research guidance
ผู้ใช้ยังเป็นผู้ตัดสินใจสุดท้ายเสมอ

## Accounting rules

- BUY เพิ่ม cost basis ด้วย `quantity × unitPrice + fee`
- SELL ลดต้นทุนด้วย average cost และคิด realized P/L จาก `proceeds − fee − removed cost`
- DIVIDEND แสดงเป็น income แยกจาก realized trade P/L
- FEE แสดงเป็นค่าธรรมเนียมแยกต่างหาก
- CASH_DEPOSIT และ CASH_WITHDRAWAL ใช้คำนวณ net cash contribution
- SELL ที่มากกว่าจำนวนถือครองจะถูกปฏิเสธ
- transaction currency ต้องตรงกับ base currency ในเฟสแรก
- เปลี่ยน base currency ไม่ได้หลังมี transaction แล้ว

## Persistence

 schema อยู่ที่ `src/main/resources/investment-schema.sql` และมีหกตาราง:

- `minikun_investment_policy`
- `minikun_investment_transaction`
- `minikun_investment_thesis`
- `minikun_investment_quote_priority`
- `minikun_investment_monitor_state`
- `minikun_investment_news_event`

ทุก query ที่อ่านหรือแก้ข้อมูลต้องมี `owner_id` เป็นเงื่อนไขเสมอ

## Configuration

```properties
minikun.investment.enabled=true
minikun.investment.default-base-currency=THB
minikun.investment.market.twelve-data.api-key=${MINIKUN_INVESTMENT_TWELVE_DATA_API_KEY:}
minikun.investment.fx.frankfurter.url=https://api.frankfurter.dev
minikun.investment.sec.ticker-url=https://www.sec.gov
minikun.investment.sec.user-agent=MinikunAgent/1.0 (contact: minikun@example.com)
minikun.investment.alpaca.key-id=${MINIKUN_INVESTMENT_ALPACA_KEY_ID:}
minikun.investment.alpaca.secret=${MINIKUN_INVESTMENT_ALPACA_SECRET:}
minikun.investment.monitor.enabled=true
minikun.investment.monitor.owner-id=default
minikun.investment.monitor.zone=Asia/Bangkok
minikun.investment.monitor.time=08:15
minikun.investment.monitor.search-results-per-symbol=3
minikun.investment.monitor.news-lookback-hours=48
minikun.investment.monitor.max-fresh-quote-symbols=8
minikun.investment.monitor.quote-rotation-slots=3
minikun.investment.monitor.quote-cache-max-age=72h
minikun.investment.quantdinger.enabled=false
minikun.investment.quantdinger.url=http://127.0.0.1:8888
minikun.investment.quantdinger.agent-token=${MINIKUN_INVESTMENT_QUANTDINGER_AGENT_TOKEN:}
```

เก็บ investment credentials ไว้ใน launcher ส่วนตัวที่
`/Volumes/minikun/homelab/java/script/minikun-agent.sh` โดยตรง แล้วรัน deploy script
เพื่อให้ LaunchAgent ใช้ค่าจาก script เดียวกัน ไม่อ่าน investment credentials จาก `.env`
อีกต่อไป; ไม่ควรใส่ key ลงใน conversation หรือ commit เข้า repository
