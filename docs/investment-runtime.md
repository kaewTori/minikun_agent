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

ยังไม่รองรับ:

- ราคาตลาดหรือ market value แบบ real-time
- หลายสกุลเงินและการแปลง FX
- corporate actions เช่น split, spin-off หรือ merger
- tax-lot accounting
- การเชื่อม broker และการส่งคำสั่งซื้อขาย

ผลจาก `investment.analyze` จึงระบุ valuation basis ว่าเป็น average cost เสมอ
และต้องไม่ถูกนำเสนอเป็นราคาหรือมูลค่าตลาดปัจจุบัน

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
- `simulate_buy` เพิ่ม hypothetical cost ให้ symbol แล้วตรวจ projected allocation กับ policy

Tool นี้เป็น read-only และไม่มี capability สำหรับส่ง order

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
```
