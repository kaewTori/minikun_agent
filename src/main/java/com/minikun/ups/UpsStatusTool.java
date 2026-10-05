package com.minikun.ups;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.tools.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** A live read-only tool, also routing explicit status questions without model-generated measurements. */
public final class UpsStatusTool implements Tool, ToolRequestRouter {
    private static final ToolDefinition DEFINITION = new ToolDefinition("ups.status",
            "Read current CLEANLINE UPS status from NUT: mains/battery mode, input/output voltage, load and battery. "
                    + "Read-only. Battery charge/runtime may be driver estimates, not battery health. "
                    + "Unavailable or stale data must never be presented as current measurements.", Map.of());
    private final NutUpsClient client;

    public UpsStatusTool(NutUpsClient client) { this.client = client; }

    @Override public ToolDefinition definition() { return DEFINITION; }
    @Override public boolean requiresExplicitConfirmation(Map<String, Object> arguments) { return false; }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        var snapshot = client.read();
        return snapshot.available() ? ToolResult.success(snapshot)
                : ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "UPS data unavailable: " + snapshot.error());
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        String text = userText == null ? "" : userText.toLowerCase(Locale.ROOT);
        if (text.contains("ประวัติ") || text.contains("ย้อนหลัง") || text.contains("history")) return Optional.empty();
        boolean target = text.matches("(?s).*\\b(?:ups|cleanline)\\b.*") || text.contains("เครื่องสำรองไฟ");
        boolean status = List.of("สถานะ", "เช็ก", "เช็ค", "ตรวจ", "ไฟเข้า", "ไฟออก", "แรงดัน", "แบต", "โหลด",
                "เหลือ", "เป็นยังไง", "เป็นอย่างไร", "วัตต์", "อุณหภูมิ", "สุขภาพ", "กำลังไฟ",
                "เวลาสำรอง", "สำรองไฟได้", "ได้นาน", "กินไฟ", "ใช้ไฟ",
                "status", "check", "voltage", "charge", "load", "runtime", "watt", "temperature", "health")
                .stream().anyMatch(text::contains);
        // Configuration, purchase advice and control requests must not become status reads.
        boolean other = List.of("ติดตั้ง", "ตั้งค่า", "เปรียบเทียบ", "ซื้อ", "ควร", "ปิดเครื่อง", "ปิดเสียง",
                "shutdown", "install", "configure", "compare", "recommend", "turn off", "beeper")
                .stream().anyMatch(text::contains);
        if (!target || !status || other) return Optional.empty();
        var snapshot = client.read();
        if (!snapshot.available()) return Optional.of(ToolEvidence.finalFailed("ups.status",
                "ตอนนี้อ่านสถานะ UPS ไม่ได้ครับ (" + snapshot.error() + ") จึงยังยืนยันค่าแบตเตอรี่หรือโหลดไม่ได้"));
        return Optional.of(ToolEvidence.finalVerified("ups.status", format(snapshot)));
    }

    private String format(NutUpsClient.Snapshot snapshot) {
        Map<String, String> values = snapshot.variables();
        List<String> flags = List.of(values.get("ups.status").split("\\s+"));
        String mode = flags.contains("OB") ? "กำลังใช้แบตเตอรี่" : flags.contains("OL") ? "ใช้ไฟบ้าน" : "ตรวจรหัสสถานะด้านล่าง";
        StringBuilder result = new StringBuilder("สถานะ UPS จาก NUT: ").append(mode)
                .append(" (").append(values.get("ups.status")).append(")\n");
        if (flags.contains("LB")) result.append("• แบตเตอรี่ต่ำ\n");
        if (flags.contains("OVER")) result.append("• โหลดเกินพิกัด\n");
        if (flags.contains("RB")) result.append("• UPS แจ้งให้เปลี่ยนแบตเตอรี่\n");
        append(result, values, "input.voltage", "แรงดันขาเข้า", " V");
        append(result, values, "output.voltage", "แรงดันขาออก", " V");
        append(result, values, "ups.load", "โหลด", "%");
        if (!values.containsKey("ups.load")) result.append("• NUT ยังไม่มีข้อมูลโหลดจากเครื่องนี้\n");
        append(result, values, "ups.realpower", "กำลังไฟจริงที่ UPS รายงาน", " W");
        if (!values.containsKey("ups.realpower")) result.append("• ยังไม่มีวัตต์ที่วัดจริงจาก UPS\n");
        append(result, values, "ups.realpower.nominal", "กำลังไฟพิกัด (สเปก)", " W");
        append(result, values, "ups.temperature", "อุณหภูมิ UPS ที่รายงาน", " °C");
        if (!values.containsKey("ups.temperature")) result.append("• ยังไม่มีข้อมูลอุณหภูมิจาก UPS\n");
        append(result, values, "battery.voltage", "แรงดันแบตเตอรี่", " V");
        append(result, values, "battery.charge", "ระดับแบตเตอรี่ที่ NUT รายงาน (อาจเป็นค่าประเมิน)", "%");
        append(result, values, "battery.status", "สถานะแบตเตอรี่ที่ UPS รายงาน", "");
        append(result, values, "ups.test.result", "ผลทดสอบแบตเตอรี่ครั้งก่อนที่ UPS รายงาน", "");
        if (!values.containsKey("battery.status") && !values.containsKey("ups.test.result")) {
            result.append("• สุขภาพแบตเตอรี่ยังไม่ทราบ; ประจุ 100% ไม่ได้ยืนยันสุขภาพแบต\n");
        }
        append(result, values, "battery.runtime", "เวลาสำรองที่ NUT รายงาน (อาจเป็นค่าประเมิน)", " วินาที");
        if (!values.containsKey("battery.runtime")) result.append("• ยังไม่มีเวลาสำรองที่เหลือ; ต้องมีข้อมูล runtime หรือกราฟสอบเทียบที่ตรงเครื่อง\n");
        return result.append("อ่านข้อมูลเมื่อ ").append(snapshot.queriedAt()
                .atZone(java.time.ZoneId.of("Asia/Bangkok"))).toString();
    }

    private void append(StringBuilder result, Map<String, String> values, String key, String label, String unit) {
        if (values.containsKey(key)) result.append("• ").append(label).append(": ").append(values.get(key)).append(unit).append('\n');
    }
}
