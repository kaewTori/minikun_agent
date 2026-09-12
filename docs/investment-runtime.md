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

- หลายสกุลเงินและการแปลง FX
- corporate actions เช่น split, spin-off หรือ merger
- tax-lot accounting
- การส่งคำสั่งเข้า broker จริง (รองรับเฉพาะ Alpaca Paper ที่ยืนยันซ้ำ)

ผลจาก `investment.analyze` ยังคงระบุ valuation basis ว่าเป็น average cost เสมอ
ส่วน `investment.data` ใช้ latest quote แบบ read-on-demand และต้องรายงานแหล่งที่มา/เวลาเสมอ

## Tools

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
- `fx` ดึง reference rate รายวันจาก Frankfurter/ECB โดยไม่ต้องใช้ key
- `sec_filings` ดึงรายการ filing ล่าสุดจาก SEC EDGAR โดยใช้ ticker mapping และ submissions API
- `paper_account` และ `paper_order` ใช้ Alpaca Paper เท่านั้น; `paper_order` ต้องยืนยันอีกครั้งและไม่แก้ immutable ledger ให้เอง

การใช้งานภายนอกเป็น read-on-demand เพื่อคุม quota; หากยังไม่มี key จะแสดง `setup_required` แทนการเดาราคา

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
