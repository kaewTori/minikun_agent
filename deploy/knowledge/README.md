# Minikun Knowledge

วางไฟล์ความรู้ส่วนตัวที่ต้องการให้มินิคุงค้นหาไว้ในโฟลเดอร์นี้ แล้วสั่ง index ผ่าน
`POST /v1/knowledge/index` โดยใช้ root ชื่อ `knowledge`

## ประเภทไฟล์ที่รองรับ

- Markdown, text, JSON, YAML และ CSV
- properties, source code, HTML และ SQL
- log และ iCalendar

## ประเภทไฟล์ที่ไม่อ่าน

- ไฟล์ซ่อนและ symlink
- `.env` และไฟล์ credential
