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
    private static final Pattern INVESTMENT_ACTION = Pattern.compile(
            "(?iu)(ทบทวน|วิเคราะห์|ตรวจสอบ|ตรวจ|เช็ก|เช็ค|ดู|สรุป|ประเมิน|ราคา|มูลค่า|ราคาปัจจุบัน|ราคาล่าสุด|"
                    + "ข่าว|วันนี้|ล่าสุด|ติดตาม|แนะนำ|มุมมอง|แผน|เพิ่ม|สร้าง|บันทึก|แก้ไข|อัปเดต|ปิด|"
                    + "review|analy[sz]e|check|show|summary|news|today|latest|monitor|advice|plan|"
                    + "price|quote|valuation|market|value|add|create|save|update|close)");
    private static final Pattern INVESTMENT_TARGET = Pattern.compile(
            "(?iu)(พอร์ต|หุ้นที่ถือ|การลงทุน|ตลาดทุน|ตลาดหุ้น|thesis|portfolio|holdings?|investment|market)");
    private static final Pattern INVESTMENT_MONITOR_TARGET = Pattern.compile(
            "(?iu)(ข่าว|market\s*news|ตลาดทุน|ตลาดหุ้น)");
    private static final Pattern INVESTMENT_MARKET_ACTION = Pattern.compile(
            "(?iu)(ราคา|มูลค่า|ราคาปัจจุบัน|ราคาล่าสุด|price|quote|valuation|market\\s*value)");
    private static final Pattern INVESTMENT_PORTFOLIO_FACT = Pattern.compile(
            "(?iu)(?:(?:พอร์ต|port(?:folio)?).*(?:มี.*(?:หุ้น|อะไร)|ถือ(?:หุ้น)?(?:อะไร|บ้าง)|ประกอบด้วย|รายการ)|"
                    + "รายการหุ้น|หุ้นที่(?:เรา|ฉัน)?ถือ|ถือหุ้น|"
                    + "(?:what|which).*(?:stocks?|shares?|positions?|hold|own|portfolio))");
    private static final Pattern INVESTMENT_MARKET_TARGET = Pattern.compile(
            "(?iu)(พอร์ต|หุ้น|การลงทุน|portfolio|holdings?|investment|market|quote)");
    private static final Pattern INVESTMENT_SYMBOL = Pattern.compile("\\b[A-Z][A-Z0-9.-]{0,7}\\b");

    boolean requiresTools(String message) {
        String value = message == null ? "" : message.toLowerCase(Locale.ROOT).trim();
        if (value.isBlank()) return false;
        if (EXPLICIT_TOOL.matcher(value).find()) return true;
        if (INVESTMENT_MARKET_ACTION.matcher(value).find()
                && (INVESTMENT_MARKET_TARGET.matcher(value).find() || INVESTMENT_SYMBOL.matcher(message).find())) return true;
        if (INVESTMENT_PORTFOLIO_FACT.matcher(value).find()) return true;
        if (INVESTMENT_MONITOR_TARGET.matcher(value).find()
                && INVESTMENT_ACTION.matcher(value).find()) return true;
        if (INVESTMENT_ACTION.matcher(value).find() && INVESTMENT_TARGET.matcher(value).find()) return true;
        return ACTION.matcher(value).find() && (TARGET.matcher(value).find() || SEQUENCE.matcher(value).find());
    }
}
