# Minikun Knowledge

วางไฟล์ความรู้ส่วนตัวที่ต้องการให้มินิคุงค้นหาไว้ในโฟลเดอร์นี้ แล้วสั่ง index ผ่าน
`POST /v1/knowledge/index` โดยใช้ root ชื่อ `knowledge`

รองรับไฟล์ UTF-8 ประเภท Markdown, text, JSON, YAML, CSV, properties, source code,
HTML, SQL, log และ iCalendar ไฟล์ซ่อน, `.env`, symlink และไฟล์ credential จะไม่ถูกอ่าน
