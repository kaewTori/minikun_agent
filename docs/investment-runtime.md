# Investment Copilot Runtime

Investment Copilot เฟสแรกเป็นระบบช่วยบันทึกและวิเคราะห์การลงทุนแบบ local และ owner-scoped
โดยตั้งใจแยกการคำนวณตัวเลขออกจาก LLM อย่างชัดเจน โมเดลมีหน้าที่เลือก tool และอธิบายผล
ส่วนยอดถือครอง ต้นทุน กำไรที่รับรู้ และ policy breach คำนวณใน Java ด้วย `BigDecimal`

## ขอบเขต

รองรับ:

- investment policy: สกุลเงินฐาน benchmark และเพดานสัดส่วนต่อหลักทรัพย์
- immutable transaction ledger: `BUY`, `SELL`, `DIVIDEND`, `FEE`, `CASH_DEPOSIT`, `CASH_WITHDRAWAL`
- ยกเลิกรายการผิดด้วย `void_transaction` โดยเก็บรายการเดิมไว้เพื่อ audit
- average-cost position และ realized profit/loss
- cost-basis allocation และคำเตือนเมื่อเกิน policy
- investment thesis, invalidation condition และรอบทบทวน
- hypothetical buy simulation แบบไม่บันทึกและไม่ส่งคำสั่ง
- external market/FX/SEC reads และ Alpaca Paper workflow แบบแยกจาก ledger

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
- `add_transaction`
- `void_transaction`
- `save_thesis`
- `close_thesis`

เมื่อเจ้าของตอบยืนยัน `InvestmentConfirmationRouter` จะ replay arguments เดิมจาก PostgreSQL
โดยใช้ `ownerId` เดิม ไม่อาศัยความจำหรือการตีความใหม่ของโมเดล

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

schema อยู่ที่ `src/main/resources/investment-schema.sql` และมีสามตาราง:

- `minikun_investment_policy`
- `minikun_investment_transaction`
- `minikun_investment_thesis`

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
```

เก็บ investment credentials ไว้ใน launcher ส่วนตัวที่
`/Volumes/minikun/homelab/java/script/minikun-agent.sh` โดยตรง แล้วรัน deploy script
เพื่อให้ LaunchAgent ใช้ค่าจาก script เดียวกัน ไม่อ่าน investment credentials จาก `.env`
อีกต่อไป; ไม่ควรใส่ key ลงใน conversation หรือ commit เข้า repository
