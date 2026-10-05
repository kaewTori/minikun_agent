# CLEANLINE UPS via NUT

มินิคุงอ่านสถานะผ่าน `ups.status` และ `GET /v1/ups/status` ด้วย NUT TCP protocol
โดยส่งเพียง `LIST VAR cleanline` ไม่มีคำสั่งควบคุม UPS หรือระบบปิดเครื่องอัตโนมัติ
ตัว client ใช้ Java standard library จึงไม่ต้องเพิ่ม Maven dependency

ถามในแชตได้ เช่น `เช็กสถานะ UPS ให้หน่อย`, `แบต UPS เหลือเท่าไหร่`
หรือ `แรงดันขาเข้า cleanline ตอนนี้เท่าไหร่` คำตอบสถานะใช้ค่าที่อ่านจาก NUT จริง
ถ้า NUT แจ้ง DATA-STALE, driver หยุด หรือ connection timeout
จะรายงานว่าอ่านไม่ได้และไม่ส่งค่าบางส่วนหรือค่าที่เคยอ่านได้กลับมา

## เฝ้าระวัง แจ้งเตือน และประวัติ

เปิด UPS monitor แล้ว โดยตรวจสถานะทุก **5 วินาที** บันทึกค่าทุก **60 วินาที**
และบันทึกเหตุการณ์ทันทีเมื่อพบการเปลี่ยนโหมด/แบตต่ำ/อ่านข้อมูลไม่ได้
เก็บไฟล์รายวันย้อนหลัง **30 วัน** ที่ `~/.minikun/ups` (directory 700 / files 600)
ไม่ขึ้นกับฐานข้อมูล, LLM หรืออินเทอร์เน็ตในการตรวจและเก็บข้อมูล
ต้องมี NUT และ process มินิคุงทำงานอยู่; ช่วงที่ agent หยุดไม่สามารถบันทึกเหตุการณ์ได้

แจ้งเฉพาะเหตุการณ์:

- `ONBATT`: ตรวจพบ UPS ใช้แบตเตอรี่
- `ONLINE`: กลับมาใช้ไฟบ้านหลังตรวจพบใช้แบต พร้อมระยะเวลาจากครั้งแรกที่ตรวจพบ
- `LOWBATT`: UPS ส่งธงแบตเตอรี่ต่ำ (ใช้ธงจากเครื่อง ไม่ตัดสินจาก battery.charge ที่ประเมิน)
- `UNAVAILABLE`: อ่านไม่ได้สองครั้งติดต่อกัน เพื่อลดแจ้งเตือนจากความขัดข้องชั่วครู่
- `COMM_RECOVERED`: กลับมาอ่านข้อมูลได้หลังเคยแจ้งว่าอ่านไม่ได้

ไม่มีข้อความซ้ำขณะที่สถานะคงเดิม เริ่ม agent ขณะใช้ไฟบ้านจะไม่แจ้งว่าไฟเพิ่งกลับมา
การแจ้งเตือนใช้ระบบเดิมของมินิคุง: หน้าเว็บ cockpit ขณะเชื่อมต่ออยู่
(หน้าเปิดอยู่แสดง toast; หน้าอยู่เบื้องหลังต้องอนุญาต browser notification)
ถ้าไม่มีหน้าเว็บเชื่อมต่อจะใช้ ntfy ที่ตั้งค่าไว้เดิม ไม่ได้สร้างช่องทางส่งใหม่
ถ้าส่งไม่ได้จะเก็บคิวลง `state.json` แล้วรอส่งใหม่ โดยแยก thread จากการตรวจสถานะ
ข้อความแสดงเวลาเหตุการณ์เดิม จึงแยกข้อความส่งล่าช้าจากสถานะปัจจุบันได้
อาจส่งซ้ำได้หาก process หยุดหลังปลายทางรับข้อความแต่ก่อนบันทึกผลสำเร็จ

ประวัติแยกจากคิวแจ้งเตือน จึงยังอ่านเหตุการณ์ได้เมื่อปลายทางแจ้งเตือนเข้าไม่ถึง
ระยะเวลาใช้แบตเป็นเวลาประมาณจากครั้งแรกที่ตรวจพบ ไม่ใช่เวลาสำรองที่เหลือ
หากมีช่วงอ่านไม่ได้หรือ agent รีสตาร์ต จะมี `timingGap=true` และข้อความระบุว่ามีช่องว่างข้อมูล
ไม่มีบันทึกไม่ได้ยืนยันว่าไม่เคยเกิดไฟดับ

ถามในแชตว่า **`ดูประวัติ UPS ย้อนหลัง`** ได้ (แสดงเหตุการณ์ล่าสุด 20 รายการ)
หรือตรวจ API ที่ใช้ management token เดียวกับสถานะ:

```sh
curl http://127.0.0.1:8080/v1/ups/monitor
curl 'http://127.0.0.1:8080/v1/ups/history?limit=100'
curl 'http://127.0.0.1:8080/v1/ups/history?limit=100&samples=true'
# ส่งข้อความที่ระบุชัดว่าเป็นการทดสอบ ไม่สร้างเหตุการณ์ไฟดับในประวัติ
curl -X POST http://127.0.0.1:8080/v1/ups/notifications/test
```

`/monitor` แสดงเวลาตรวจล่าสุด จำนวนข้อความรอส่ง และข้อผิดพลาดในการเก็บ/ส่งข้อมูล
`/history` คืนรายการล่าสุดก่อน จำกัด 1–500 รายการ
monitor ไม่ส่งคำสั่งปิดเครื่อง ปิด UPS เปลี่ยนเสียง หรือทดสอบแบตเตอรี่
ทดสอบการเปลี่ยนสถานะและ retry ด้วย NUT snapshots จำลอง รวมถึง restart, DATA-STALE,
สถานะที่ไม่ทราบ, การอ่านไฟล์หลังมีบรรทัดเขียนไม่ครบ และ authorization ของ API
ตรวจบน Mac จริงแล้วว่า monitor ตรวจต่อเนื่อง, บันทึกไฟล์และอ่านประวัติผ่าน HTTPS/แชตได้
ส่งข้อความ `UPS_TEST` ผ่าน ntfy ได้ HTTP 200 โดยไม่เพิ่มเหตุการณ์ไฟดับจำลองลงประวัติจริง
ตัวอ่านไฟล์ใช้สำเนา ObjectMapper ที่ลงทะเบียน JavaTimeModule เอง
เพื่อรองรับวันที่แม้ mapper หลักของแอปยังไม่ได้ลงทะเบียน module นี้

## เครื่องที่ทดสอบ

ทดสอบวันที่ 5 ตุลาคม 2026 บน Mac mini / macOS, NUT 2.8.5, libusb 1.0.30:

- USB: INNO TECH `USB to Serial`, vendor/product `0665:5161`
- รุ่นบนสติกเกอร์ที่ผู้ใช้ยืนยัน: **D-2000L — 2000VA / 1200W**
- Driver: `nutdrv_qx`, protocol ที่ตรวจพบ: `voltronic-qs-hex`, firmware `PM-T`
- อ่านได้: `ups.status`, `input.voltage`, `output.voltage`, `output.frequency`,
  `battery.voltage`, `battery.charge`, สถานะเสียงเตือน และ nominal ratings
- Driver แจ้งว่า battery charge เป็น **ค่าประเมินจากแรงดัน**
  ไม่ใช่ค่าความจุหรือสุขภาพแบตเตอรี่ที่วัดโดยตรง
- NUT 2.8.5 เดิมไม่มี `ups.load` เพราะบั๊ก format validation ของ QS-Hex
  ติดตั้งไดรเวอร์ที่แก้ `%d` เป็น `%ld` ตาม upstream แล้ว
  ตรวจด้วย `upsc`, HTTPS endpoint และคำตอบแชตว่าอ่าน `ups.load` ได้จริง
  ค่าแรกที่ UPS รายงานคือ **0%**; ไม่ใช่การยืนยันว่ากำลังไฟจริงเท่ากับ 0W
- `ups.realpower.nominal=1200` และ `ups.power.nominal=2000` เป็นข้อมูลสเปก
  ที่ตั้งตามสติกเกอร์ ไม่ใช่กำลังไฟที่วัดได้ในขณะนั้น
- ไม่ได้ทดสอบตัดไฟบ้าน ถอดสาย USB จริง หรือรีบูตเครื่องที่กำลังใช้งาน
  การทดสอบซอฟต์แวร์ครอบคลุม stale data, connection failure, timeout และ incomplete data

## ค่าเพิ่มเติมและข้อจำกัดของเครื่องนี้

| ข้อมูล | สิ่งที่อ่านได้ / วิธีได้ข้อมูลเพิ่ม |
| --- | --- |
| โหลด (%) | QS-Hex มีค่าโหลด; NUT 2.8.5 ต้องใช้ตัวแก้บั๊กด้านล่าง |
| วัตต์จริง | ไม่มี `ups.realpower` จากเครื่องนี้; ต้องมีมิเตอร์วัดกำลังจริงที่ฝั่งโหลดเพื่อได้วัตต์ของอุปกรณ์ที่ UPS จ่ายไฟให้ |
| เวลาสำรอง | ยังไม่มี `battery.runtime`; NUT ประเมินด้วย `runtimecal` ได้เมื่อมีเวลาสำรองที่ทราบจริงสองจุดที่โหลดต่างกัน |
| อุณหภูมิ | QS แบบ T ที่เครื่องนี้ใช้ไม่มีช่องอุณหภูมิ; เซนเซอร์ภายนอกวัดอุณหภูมิรอบเครื่อง/ผิวเครื่องได้ แต่ไม่เท่ากับเซนเซอร์ภายใน |
| สุขภาพแบตเตอรี่ | ไม่มีค่า SoH หรือผลทดสอบครั้งก่อน; ต้องใช้การทดสอบความจุ/เวลาสำรองเทียบฐานเดิมเพื่อประเมินการเสื่อม |

แค็ตตาล็อก D Series ของผู้ผลิตยืนยันรุ่นและพิกัด แต่ระบุเวลาสำรองเพียงประมาณ
10–30 นาทีขึ้นกับอุปกรณ์ ไม่มีกราฟโหลดต่อเวลาหรือจุดสอบเทียบสองจุดที่ใช้กับ `runtimecal` ได้
จึงไม่ตั้งค่า runtimecal จากตัวเลขทั่วไป ไม่คูณโหลด % ด้วย 1200 แล้วอ้างว่าเป็นวัตต์ที่วัดจริง
และไม่แปลงประจุแบต 100% เป็นสุขภาพแบต 100%
คู่มือผู้ผลิตระบุว่ามีการแจ้งแบตเสื่อมหลังทดสอบผ่านโปรแกรม
แต่สถานะ USB ที่อ่านในครั้งนี้ไม่มีค่า SoH หรือผลทดสอบย้อนหลังให้ดึงมา
การทดสอบสั้นอาจช่วยคัดกรองแบตที่มีปัญหา แต่ไม่เท่ากับการวัดความจุที่เหลือเป็นเปอร์เซ็นต์
การตรวจครั้งนี้ไม่ได้เริ่ม self-test หรือ discharge test บนอุปกรณ์ที่ใช้งานอยู่

มินิคุงรองรับตัวแปรวัตต์ อุณหภูมิ เวลาสำรอง และสถานะแบตเมื่อ NUT ส่งมาจริง
หากไม่มีค่าจะบอกว่าไม่มีข้อมูลอย่างชัดเจน

## แก้ค่าโหลดที่หายไปใน NUT 2.8.5 บน Mac

ต้นเหตุที่ upstream รายงานคือ `ups.load` ใช้ format `%d` แต่ตัวแปลงส่ง `%ld`
ไดรเวอร์จึงตัดตัวแปรนี้ออก สคริปต์ `deploy/build-nut-load-fix.sh` ดาวน์โหลด source
รุ่น 2.8.5 จาก NUT ตรวจ SHA-256 แล้วแก้เฉพาะ format นี้และเพิ่มชื่อเวอร์ชันเพื่อระบุ backport
ต้องมี Xcode Command Line Tools, Homebrew NUT/libusb และ Python 3

```sh
/bin/sh deploy/build-nut-load-fix.sh
# คัดลอกไฟล์จาก path ที่สคริปต์แสดงเป็น deploy/nutdrv_qx.load-fixed
sudo /bin/sh deploy/install-nut-macos.sh --system-driver
/opt/homebrew/opt/nut/bin/upsc cleanline@127.0.0.1
```

ไฟล์ไดรเวอร์ที่แก้แล้วติดตั้งที่ `/usr/local/libexec/minikun-nut/nutdrv_qx`
โดยไม่เขียนทับไดรเวอร์ Homebrew ค่า `driver.version.data` จะลงท้ายด้วย `minikun-load-fix`
หาก administrator helper อ่าน repo บน external volume ไม่ได้ ให้คัดลอก installer,
plist และ `nutdrv_qx.load-fixed` ไปยังโฟลเดอร์เดียวกันใน `/private/tmp` ก่อนรัน

Source SHA-256: `18bf32e59eb764b13da3c4fa70384926d7fa584cb31d2fe7f137a570633eeec1`

ย้อนกลับไปใช้ไดรเวอร์ Homebrew:

```sh
sudo /bin/sh deploy/install-nut-macos.sh --system-driver --stock-driver
```

## macOS

```sh
/bin/sh deploy/install-nut-macos.sh
sudo /bin/sh deploy/install-nut-macos.sh --system-driver
/opt/homebrew/opt/nut/bin/upsc cleanline@127.0.0.1
curl http://127.0.0.1:8080/v1/ups/status
```

ตัวติดตั้งรักษาไฟล์ configuration เดิมเมื่อรันซ้ำ ต้องใช้บัญชีผู้ใช้ปกติในขั้นแรก
และสิทธิ์ผู้ดูแลในขั้น `--system-driver` เท่านั้น ถ้าหน้าต่าง macOS administrator helper
อ่านตัวติดตั้งบน external volume ไม่ได้ ให้คัดลอก script และ plist ไปยังโฟลเดอร์ชั่วคราว
ใน `/private/tmp` ก่อนรันขั้นผู้ดูแล

- `/opt/homebrew/etc/nut/{ups.conf,upsd.conf,upsd.users,nut.conf}`
- USB driver: `/Library/LaunchDaemons/com.minikun.nut-driver.plist`, เริ่มตอนบูต
- NUT data server: `~/Library/LaunchAgents/com.minikun.nut-server.plist`, เริ่มตอน login
- Log driver: `/var/log/minikun-nut-driver.log`
- Log server: `~/Library/Logs/Minikun/nut-server.log`

บน Mac เครื่องนี้ Apple HID driver จับ USB ไว้ จึงต้องให้เฉพาะ `nutdrv_qx` รันเป็น root
และให้ socket ใช้กลุ่ม staff เพื่อให้ `upsd` ที่รันเป็นผู้ใช้ปกติอ่านได้
Launchd จะเริ่มบริการใหม่เมื่อ process จบ
ไม่ใช้ `brew services start nut` เพราะสูตร Homebrew เริ่ม `upsmon` ซึ่งไม่จำเป็นสำหรับการอ่านสถานะ

NUT ฟังเฉพาะ `127.0.0.1:3493`, ไม่มี control user ใน `upsd.users`, และไม่มี `upsmon`
ค่า `driver.flag.allow_killpower` ที่ตรวจได้เป็น `0`
REST endpoint ใช้ `X-Minikun-System-Token` เมื่อกำหนด management token เดียวกับระบบ health

ตรวจบริการ:

```sh
launchctl print system/com.minikun.nut-driver
launchctl print "gui/$(id -u)/com.minikun.nut-server"
```

## ตั้งค่ามินิคุง / ย้ายไป Linux

| Environment variable | Default |
| --- | --- |
| `MINIKUN_UPS_ENABLED` | `true` |
| `MINIKUN_UPS_HOST` | `127.0.0.1` |
| `MINIKUN_UPS_PORT` | `3493` |
| `MINIKUN_UPS_NAME` | `cleanline` |
| `MINIKUN_UPS_TIMEOUT` | `2s` |
| `MINIKUN_UPS_MONITOR_ENABLED` | `true` |
| `MINIKUN_UPS_POLL_INTERVAL_MS` | `5000` |
| `MINIKUN_UPS_HISTORY_DIRECTORY` | `${user.home}/.minikun/ups` |

เมื่อย้าย UPS และ agent ไป Linux เครื่องเดียวกัน ค่าเหล่านี้ใช้เดิมได้
บน Debian/Ubuntu ติดตั้ง `nut-server` แล้วตั้ง `/etc/nut/nut.conf` เป็น `MODE=netserver`
ตั้ง `/etc/nut/ups.conf` ดังนี้:

```ini
pollinterval = 5
[cleanline]
    driver = nutdrv_qx
    port = auto
    vendorid = 0665
    productid = 5161
    protocol = voltronic-qs-hex
    desc = "CLEANLINE D-2000L USB"
    default.ups.mfr = CLEANLINE
    default.ups.model = D-2000L
    default.ups.power.nominal = 2000
    default.ups.realpower.nominal = 1200
    pollfreq = 30
```

ตั้ง `/etc/nut/upsd.conf` เป็น `LISTEN 127.0.0.1 3493` และไม่เพิ่ม control user
ใช้ driver/server service ที่ distribution จัดมาให้ โดยไม่เปิด `nut-monitor`/`upsmon`
ตรวจชื่อ service จริงด้วย `systemctl list-unit-files 'nut*'` เพราะแตกต่างกันตามเวอร์ชัน
ใช้กฎ udev ของแพ็กเกจ NUT สำหรับสิทธิ์ USB; ไม่ต้องยกสิทธิ์ agent เป็น root
ทดสอบ `upsc cleanline@127.0.0.1` ก่อนเปิดมินิคุง
หากใช้ NUT 2.8.5 ให้ตรวจว่าแพ็กเกจมี QS-Hex load-format fix แล้ว
หรือต้อง backport ตาม issue ด้านล่าง; การตั้งค่าพิกัดอย่างเดียวไม่แก้บั๊กนี้

ถ้า UPS อยู่ Linux แต่ agent ยังอยู่ Mac ให้ใช้ SSH tunnel ส่ง local port 3493
ไป Linux แทนการเปิด NUT anonymous TCP ออกทั้งเครือข่าย:

```sh
# ต้องหยุด local NUT server บน Mac ก่อนใช้ local port เดียวกัน
ssh -N -L 127.0.0.1:3493:127.0.0.1:3493 your-linux-server
```

## แหล่งอ้างอิง

- [NUT Qx driver](https://networkupstools.org/docs/man/nutdrv_qx.html)
- [NUT LIST VAR protocol](https://networkupstools.org/docs/developer-guide.chunked/net-protocol.html)
- [NUT data server configuration](https://networkupstools.org/docs/man/upsd.conf.html)
- [CLEANLINE D Series](https://www.powermatic.co.th/product/d-1500k/)
- [CLEANLINE D Series catalogue](https://drive.google.com/file/d/1vtc7vvyFFEJj-YKNvNHLUNpmvVGgXn2R/view)
- [CLEANLINE D Series manual](https://drive.google.com/file/d/1uTk0jnNYq2rStjbOBfw8U9T-XPM44feT/view)
- [NUT QS-Hex load bug #3532](https://github.com/networkupstools/nut/issues/3532)
- [Voltronic QS protocol: P/T/V fields](https://networkupstools.org/protocols/voltronic-qs.html)
