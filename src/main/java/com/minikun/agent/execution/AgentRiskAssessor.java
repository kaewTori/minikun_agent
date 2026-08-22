package com.minikun.agent.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Cheap deterministic risk gate used before a personal agent starts a plan. */
public final class AgentRiskAssessor {
    private static final Pattern CRITICAL = Pattern.compile(
            "(?iu)(?:โอนเงิน|จ่ายเงิน|ซื้อของ|ลงทุน|ถอนเงิน|รหัสผ่าน|เงินทั้งหมด|ลบ(?:ข้อมูล|ไฟล์)?ถาวร|ลบข้อมูล|"
                    + "\\b(?:password|credentials?|pay|purchase|buy|invest|withdraw|transfer(?:\\s+(?:all\\s+)?(?:my\\s+)?money)?|"
                    + "factory\\s+reset|shutdown|reboot|wipe)\\b)");
    private static final Pattern HIGH = Pattern.compile(
            "(?iu)(?:ส่งข้อความ|ส่งอีเมล|เผยแพร่|โพสต์|แชร์|แก้ไขไฟล์|ลบไฟล์|ลบข้อมูล|สั่งงาน|ควบคุม|"
                    + "\\b(?:send|email|publish|post|share|delete|modify|write|computer)\\b)");
    private static final Pattern MEDIUM = Pattern.compile(
            "(?iu)(?:สร้าง|เพิ่ม|บันทึก|ตั้งเตือน|นัดหมาย|แก้รายการ|สร้างงาน|"
                    + "\\b(?:create|update|save|schedule|remind|task|calendar)\\b)");

    public AgentRiskAssessment assess(String objective, List<String> steps) {
        String value = (objective == null ? "" : objective) + " "
                + String.join(" ", steps == null ? List.of() : steps);
        String normalized = value.toLowerCase(Locale.ROOT);
        List<String> reasons = new ArrayList<>();
        if (CRITICAL.matcher(normalized).find()) {
            reasons.add("financial, credential, destructive, or system-critical action detected");
            return new AgentRiskAssessment(AgentRiskLevel.CRITICAL, reasons);
        }
        if (HIGH.matcher(normalized).find()) {
            reasons.add("external side effect or local write action detected");
            return new AgentRiskAssessment(AgentRiskLevel.HIGH, reasons);
        }
        if (MEDIUM.matcher(normalized).find()) {
            reasons.add("persistent personal state change detected");
            return new AgentRiskAssessment(AgentRiskLevel.MEDIUM, reasons);
        }
        return new AgentRiskAssessment(AgentRiskLevel.LOW, reasons);
    }
}
