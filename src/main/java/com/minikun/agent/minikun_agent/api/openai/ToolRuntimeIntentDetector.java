package com.minikun.agent.minikun_agent.api.openai;

import java.util.Locale;
import java.util.regex.Pattern;

/** Keeps ordinary answers on direct streaming while preserving explicit personal-agent actions. */
final class ToolRuntimeIntentDetector {
    private static final Pattern EXPLICIT_TOOL = Pattern.compile(
            "(?iu)(ใช้\\s*(?:เครื่องมือ|tool)|เรียก\\s*(?:เครื่องมือ|tool)|use (?:a )?tool)");
    private static final Pattern ACTION = Pattern.compile(
            "(?iu)(เพิ่ม|สร้าง|บันทึก|แก้ไข|อัปเดต|ลบ|ย้าย|ส่ง|ตั้ง|รัน|เปิด|ตรวจ|เช็ก|เช็ค|ดำเนินการ|"
                    + "create|add|save|update|delete|remove|move|send|schedule|run|open|check|execute)");
    private static final Pattern TARGET = Pattern.compile(
            "(?iu)(งาน|เตือน|ปฏิทิน|เป้าหมาย|ไฟล์|โฟลเดอร์|คอมพิวเตอร์|เซิร์ฟเวอร์|โฮมแล็บ|ระบบ|"
                    + "เว็บ|เว็บไซต์|ลิงก์|ความจำ|task|reminder|calendar|goal|file|folder|computer|server|"
                    + "homelab|system|service|website|url|link|memory|portfolio|investment)");
    private static final Pattern SEQUENCE = Pattern.compile(
            "(?iu)(จากนั้น|แล้วค่อย|ต่อด้วย|เสร็จแล้ว|and then|then)");

    boolean requiresTools(String message) {
        String value = message == null ? "" : message.toLowerCase(Locale.ROOT).trim();
        if (value.isBlank()) return false;
        if (EXPLICIT_TOOL.matcher(value).find()) return true;
        return ACTION.matcher(value).find() && (TARGET.matcher(value).find() || SEQUENCE.matcher(value).find());
    }
}
