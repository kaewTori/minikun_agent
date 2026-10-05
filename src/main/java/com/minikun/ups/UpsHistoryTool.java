package com.minikun.ups;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.tools.*;
import java.io.IOException;
import java.time.ZoneId;
import java.util.*;

public final class UpsHistoryTool implements Tool, ToolRequestRouter {
    private final UpsMonitor monitor;
    public UpsHistoryTool(UpsMonitor monitor) { this.monitor = monitor; }

    @Override public ToolDefinition definition() {
        return new ToolDefinition("ups.history", "Read recorded UPS events from the last 30 days, newest first. "
                + "Read-only; durations start at first observation, monitoring gaps are marked. No record is not proof of no outage.", Map.of());
    }
    @Override public boolean requiresExplicitConfirmation(Map<String, Object> arguments) { return false; }
    @Override public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        try { return ToolResult.success(monitor.recent(100, false)); }
        catch (IOException exception) { return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "UPS history unavailable"); }
    }
    @Override public Optional<ToolEvidence> route(String text, ConversationId conversation) {
        String normalized = Objects.requireNonNullElse(text, "").toLowerCase(Locale.ROOT);
        boolean target = normalized.matches("(?s).*\\b(?:ups|cleanline)\\b.*") || normalized.contains("เครื่องสำรองไฟ");
        if (!target || !(normalized.contains("ประวัติ") || normalized.contains("ย้อนหลัง") || normalized.contains("history")))
            return Optional.empty();
        try {
            List<UpsHistory.Entry> entries = monitor.recent(20, false);
            StringBuilder message = new StringBuilder("ประวัติ UPS ที่มินิคุงบันทึกไว้ (ล่าสุดก่อน):\n");
            for (var entry : entries) message.append("• ").append(entry.at().atZone(ZoneId.of("Asia/Bangkok")))
                    .append(" — ").append(entry.message()).append('\n');
            if (entries.isEmpty()) message.append("ยังไม่มีเหตุการณ์ที่บันทึกไว้\n");
            message.append("เก็บย้อนหลัง 30 วัน; ช่วงที่มินิคุงหยุดไม่มีข้อมูล และไม่มีบันทึกไม่ได้ยืนยันว่าไม่เคยไฟดับ");
            return Optional.of(ToolEvidence.finalVerified("ups.history", message.toString()));
        } catch (IOException exception) {
            return Optional.of(ToolEvidence.finalFailed("ups.history", "ตอนนี้อ่านประวัติ UPS ไม่ได้ครับ"));
        }
    }
}
