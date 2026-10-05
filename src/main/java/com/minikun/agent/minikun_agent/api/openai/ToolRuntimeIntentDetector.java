package com.minikun.agent.minikun_agent.api.openai;

import java.util.Locale;
import java.util.regex.Pattern;

/** Keeps ordinary answers on direct streaming while preserving explicit personal-agent actions. */
final class ToolRuntimeIntentDetector {
    private static final Pattern EXPLICIT_TOOL = Pattern.compile(
            "(?iu)(ใช้\\s*(?:เครื่องมือ|tool)|เรียก\\s*(?:เครื่องมือ|tool)|use (?:a )?tool)");
    private static final Pattern ACTION = Pattern.compile(
            "(?iu)(เพิ่ม|สร้าง|บันทึก|แก้ไข|อัปเดต|ลบ|ย้าย|ส่ง|ตั้ง|รัน|เปิด|ตรวจ|เช็ก|เช็ค|ดำเนินการ|ดูแล|จัดการ|ซ่อม|ทดสอบ|"
                    + "create|add|save|update|delete|remove|move|send|schedule|run|open|check|execute|repair|fix|build|test)");
    private static final Pattern TARGET = Pattern.compile(
            "(?iu)(งาน|เตือน|ปฏิทิน|เป้าหมาย|ไฟล์|โฟลเดอร์|คอมพิวเตอร์|เซิร์ฟเวอร์|โฮมแล็บ|ระบบ|บริการ|โปรเจกต์|โค้ด|"
                    + "เว็บ|เว็บไซต์|ลิงก์|ความจำ|task|reminder|calendar|goal|file|folder|computer|server|"
                    + "homelab|system|service|website|url|link|memory|portfolio|investment|repository|project|browser|tests?)");
    private static final Pattern SEQUENCE = Pattern.compile(
            "(?iu)(จากนั้น|แล้วค่อย|ต่อด้วย|เสร็จแล้ว|and then|then)");
    private static final Pattern PRESENTATION_REQUEST = Pattern.compile(
            "(?iu)(?:(?:make|create|build|generate|design|prepare|draft|produce)\\s+(?:(?:me|us|an?|the|my|our)\\s+)?"
                    + ".{0,48}\\b(?:slide\\s+deck|slides?|presentations?|powerpoints?)\\b|"
                    + "(?:ทำ|สร้าง|จัดทำ|ออกแบบ|ร่าง|จัด)(?:\\s*(?:เป็น|ให้|ออกมา))?\\s*.{0,24}"
                    + "(?:สไลด์|พรีเซนเทชัน|พาวเวอร์พอยต์|presentation|powerpoint)|"
                    + "(?:อยากได้|ขอ)(?:\\s*.{0,12})?(?:สไลด์|พรีเซนเทชัน|powerpoint).{0,20}(?:ให้หน่อย|ด้วย|เลย|ครับ|ค่ะ)?|"
                    + "(?:สไลด์|พรีเซนเทชัน|powerpoint).{0,18}(?:ให้หน่อย|ด้วย|เลย|ให้เรา))");
    private static final Pattern PRESENTATION_DISCUSSION = Pattern.compile(
            "(?iu)(?:วางแผน|ทำแผน|แผนให้|แผนการ|ขั้นตอนการ|วิธี(?:ทำ|สร้าง|แก้|ใช้)|กระบวนการ|how\\s+to|plan\\s+to)"
                    + ".{0,80}(?:slides?|slide\\s+deck|presentation|powerpoint|สไลด์|พรีเซนเทชัน)");
    private static final Pattern PRESENTATION_REVISION = Pattern.compile(
            "(?iu)(?:(?:แก้|แก้ไข|ปรับ|ย่อ|เปลี่ยน|เพิ่ม|ลบ|rewrite|revise|edit|update|change|shorten)"
                    + ".{0,40}(?:slides?|slide\\s+deck|presentation|powerpoint|สไลด์|พรีเซนเทชัน)|"
                    + "(?:slides?|slide\\s+deck|presentation|powerpoint|สไลด์|พรีเซนเทชัน)"
                    + ".{0,40}(?:แก้|แก้ไข|ปรับ|ย่อ|เปลี่ยน|เพิ่ม|ลบ|rewrite|revise|edit|update|change|shorten))");
    private static final Pattern INVESTMENT_ACTION = Pattern.compile(
            "(?iu)(ทบทวน|วิเคราะห์|ตรวจสอบ|ตรวจ|เช็ก|เช็ค|ดู|สรุป|ประเมิน|ราคา|มูลค่า|ราคาปัจจุบัน|ราคาล่าสุด|"
                    + "ข่าว|วันนี้|ล่าสุด|ติดตาม|แนะนำ|มุมมอง|แผน|เพิ่ม|สร้าง|บันทึก|แก้ไข|อัปเดต|ปิด|"
                    + "review|analy[sz]e|check|show|summary|news|today|latest|monitor|advice|plan|"
                    + "price|quote|valuation|market|value|add|create|save|update|close)");
    private static final Pattern INVESTMENT_TARGET = Pattern.compile(
            "(?iu)(พอร์ต|หุ้นที่ถือ|การลงทุน|ตลาดทุน|ตลาดหุ้น|thesis|portfolio|holdings?|investment|market)");
    private static final Pattern INVESTMENT_MONITOR_TARGET = Pattern.compile(
            "(?iu)(ข่าว|market\s*news|ตลาดทุน|ตลาดหุ้น)");
    private static final Pattern INVESTMENT_MARKET_ANALYSIS = Pattern.compile(
            "(?iu)(?:(?:วิเคราะห์|ประเมิน|ศึกษา|research|analy[sz]e|analysis)\\s*(?:the\\s+)?"
                    + "(?:ตลาด(?:หุ้น|ทุน)?|หุ้น|stock\\s*market|stocks?|market)|"
                    + "(?:ตลาดหุ้น|ตลาดทุน|stock\\s*market|stocks?|market)\\s*(?:วิเคราะห์|analysis|research|analy[sz]e))");
    private static final Pattern INVESTMENT_MARKET_ACTION = Pattern.compile(
            "(?iu)(ราคา|มูลค่า|ราคาปัจจุบัน|ราคาล่าสุด|price|quote|valuation|market\\s*value)");
    private static final Pattern INVESTMENT_PORTFOLIO_TARGET = Pattern.compile(
            "(?iu)(?:พอร์ต|\\bport(?:folio)?\\b|\\bholdings?\\b|\\bpositions?\\b|"
                    + "หุ้นที่(?:เรา|ฉัน|ผม)?(?:กำลัง)?ถือ|หุ้นในพอร์ต|รายการ(?:หุ้น|ลงทุน)|"
                    + "(?:ข้อมูล|รายละเอียด|สถานะ|ภาพรวม)\\s*(?:ของ\\s*)?(?:พอร์ต|การลงทุน)|"
                    + "(?:portfolio|holdings?|positions?)\\s*(?:ของ|ของฉัน|ของเรา)?)");
    private static final Pattern INVESTMENT_PORTFOLIO_OWNERSHIP = Pattern.compile(
            "(?iu)(?:เราถือ|ฉันถือ|ผมถือ|ในพอร์ต|\\bmy\\b|"
                    + "\\b(?:i|we)\\s+(?:hold|own)\\b)");
    private static final Pattern INVESTMENT_PORTFOLIO_INVENTORY = Pattern.compile(
            "(?iu)(?:มี|ถือ|อยู่|อะไร|ไหน|บ้าง|รายการ|ตัวไหน|ตัวใด|ประกอบ(?:ด้วย)?|แสดง|ดู|"
                    + "เช็ก|เช็ค|ตรวจสอบ|ข้อมูล|รายละเอียด|สถานะ|ภาพรวม|สรุป|เป็นยังไง|เป็นอย่างไร|"
                    + "ตอนนี้|ปัจจุบัน|what|which|show|list|have|hold|own|currently|current|overview|status|details?)");
    private static final Pattern INVESTMENT_PORTFOLIO_ANALYSIS = Pattern.compile(
            "(?iu)(วิเคราะห์|ทบทวน|แนะนำ|มุมมอง|ความเสี่ยง|ผลตอบแทน|กำไร|ขาดทุน|สัดส่วน|"
                    + "ซื้อ|ขาย|ควร|ข่าว|ราคา|มูลค่า|review|analy[sz]e|recommend|risk|performance|"
                    + "return|profit|loss|allocation|buy|sell|price|quote|valuation|news)");
    private static final Pattern INVESTMENT_MARKET_TARGET = Pattern.compile(
            "(?iu)(พอร์ต|หุ้น|การลงทุน|portfolio|holdings?|investment|market|quote)");
    private static final Pattern INVESTMENT_SYMBOL = Pattern.compile("\\b[A-Z][A-Z0-9.-]{0,7}\\b");
    private static final Pattern INVESTMENT_TRADE_ACTION = Pattern.compile(
            "(?iu)(ซื้อ|ขาย|buy|sell|bought|sold)");
    private static final Pattern INVESTMENT_TRADE_COMPLETED = Pattern.compile(
            "(?iu)(แล้ว|ไปแล้ว|เรียบร้อย|สำเร็จ|เพิ่ง|เมื่อวาน|วันนี้|already|recently|yesterday|today)");
    private static final Pattern INVESTMENT_LEDGER_ACTION = Pattern.compile(
            "(?iu)(บันทึก|เพิ่มรายการ|เพิ่มธุรกรรม|record|log|register)");
    private static final Pattern INVESTMENT_TRADE_REPORT = Pattern.compile(
            "(?ium)^\\s*(?:[-*•]\\s*)?[A-Z][A-Z0-9.-]{0,7}\\s+"
                    + "(?:(?:ขาย\\s*)?[0-9]+(?:\\.[0-9]+)?\\s*"
                    + "(?:(?:หุ้น|shares?|units?)\\s*(?:(?:@|at|ราคา|price)\\s*)?|"
                    + "(?:@|at|ราคา|price)\\s*))?"
                    + "[0-9]+(?:\\.[0-9]+)?\\s*(?:[A-Z]{3})?.*?"
                    + "(?:ได้มา|ได้รับ|net\\s*(?:proceeds?)?|received|got)\\s+"
                    + "[0-9]+(?:\\.[0-9]+)?\\s*(?:[A-Z]{3})?\\s*$");

    boolean requiresTools(String message) {
        String value = message == null ? "" : message.toLowerCase(Locale.ROOT).trim();
        if (value.isBlank()) return false;
        if (PRESENTATION_REVISION.matcher(value).find() && !PRESENTATION_DISCUSSION.matcher(value).find()) return true;
        if (requestsPresentationDeliverable(value)) return true;
        if (EXPLICIT_TOOL.matcher(value).find()) return true;
        if (INVESTMENT_TRADE_REPORT.matcher(message == null ? "" : message).find()) return true;
        if (INVESTMENT_MARKET_ANALYSIS.matcher(value).find()) return false;
        if (INVESTMENT_MARKET_ACTION.matcher(value).find()
                && (INVESTMENT_MARKET_TARGET.matcher(value).find() || INVESTMENT_SYMBOL.matcher(message).find())) return true;
        if (investmentTransactionRequiresTools(message, value)) return true;
        if (portfolioInventoryRequiresTools(value)) return true;
        if (INVESTMENT_MONITOR_TARGET.matcher(value).find()
                && INVESTMENT_ACTION.matcher(value).find()) return true;
        if (INVESTMENT_ACTION.matcher(value).find() && INVESTMENT_TARGET.matcher(value).find()) return true;
        return ACTION.matcher(value).find() && (TARGET.matcher(value).find() || SEQUENCE.matcher(value).find());
    }

    boolean requestsPresentationDeliverable(String message) {
        String value = message == null ? "" : message.toLowerCase(Locale.ROOT).trim();
        return !value.isBlank() && PRESENTATION_REQUEST.matcher(value).find()
                && !PRESENTATION_DISCUSSION.matcher(value).find();
    }

    private boolean investmentTransactionRequiresTools(String message, String value) {
        boolean target = INVESTMENT_TARGET.matcher(value).find()
                || INVESTMENT_MARKET_TARGET.matcher(value).find()
                || INVESTMENT_SYMBOL.matcher(message).find();
        return target && (INVESTMENT_LEDGER_ACTION.matcher(value).find()
                || INVESTMENT_TRADE_ACTION.matcher(value).find()
                        && INVESTMENT_TRADE_COMPLETED.matcher(value).find());
    }

    private boolean portfolioInventoryRequiresTools(String value) {
        boolean target = INVESTMENT_PORTFOLIO_TARGET.matcher(value).find();
        boolean ownership = INVESTMENT_PORTFOLIO_OWNERSHIP.matcher(value).find();
        return INVESTMENT_PORTFOLIO_INVENTORY.matcher(value).find()
                && (target || ownership)
                && !INVESTMENT_PORTFOLIO_ANALYSIS.matcher(value).find()
                && !INVESTMENT_MONITOR_TARGET.matcher(value).find();
    }
}
