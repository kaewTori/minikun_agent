package com.minikun.tools;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.guardian.GuardianFinding;
import com.minikun.guardian.GuardianReport;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Routes unambiguous homelab inspection requests directly to verified diagnostics. */
@Component
@ConditionalOnProperty(name = "minikun.guardian.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(HomelabGuardianTool.class)
public final class HomelabGuardianRouter implements ToolRequestRouter {
    private static final String TOOL_NAME = "homelab.guardian";
    private final ToolExecutor executor;

    public HomelabGuardianRouter(ToolExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "tool executor must not be null");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        return route(userText, conversationId, "default");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId) {
        if (!inspectionIntent(userText)) return Optional.empty();
        String callId = "guardian-inspect-" + UUID.randomUUID();
        ToolResult result = executor.execute(new ToolCallContext(conversationId, callId, ownerId),
                new ToolCall(callId, TOOL_NAME, Map.of("action", "inspect")));
        if (!result.success() || !(result.value() instanceof GuardianReport report)) {
            return Optional.of(ToolEvidence.finalFailed(TOOL_NAME,
                    "ขออภัยครับ ตอนนี้มินิคุงตรวจ homelab ไม่สำเร็จ"));
        }
        return Optional.of(ToolEvidence.finalVerified(TOOL_NAME, format(report)));
    }

    private boolean inspectionIntent(String text) {
        if (text == null || text.isBlank()) return false;
        String normalized = text.toLowerCase(Locale.ROOT);
        boolean homelab = normalized.contains("homelab") || normalized.contains("home lab")
                || normalized.contains("โฮมแลบ");
        boolean inspect = normalized.contains("ตรวจ") || normalized.contains("เช็ก")
                || normalized.contains("เช็ค") || normalized.contains("สถานะ")
                || normalized.contains("inspect") || normalized.contains("check")
                || normalized.contains("health") || normalized.contains("เป็นยังไง");
        return homelab && inspect;
    }

    private String format(GuardianReport report) {
        StringBuilder result = new StringBuilder("ผลตรวจ Homelab ที่ยืนยันจากระบบ: ")
                .append(report.status()).append('\n');
        if (report.findings().isEmpty()) {
            result.append("• CPU, memory, disk, process และ dependency ที่กำหนดไว้ไม่พบปัญหาครับ");
        } else {
            for (GuardianFinding finding : report.findings()) {
                result.append("• [").append(finding.severity()).append("] ")
                        .append(finding.component()).append(": ").append(finding.summary());
                if (!finding.evidence().isBlank()) result.append(" (").append(finding.evidence()).append(')');
                result.append("\n  ").append(finding.causeConfidence().equals("CONFIRMED")
                        ? "สาเหตุของคำเตือนที่ยืนยันได้: " : "ผลสืบเบื้องต้น (ยังไม่ยืนยันต้นเหตุ): ")
                        .append(finding.cause());
                if (!finding.recommendedAction().isBlank()) {
                    result.append("\n  แนะนำ: ").append(finding.recommendedAction());
                }
                result.append('\n');
            }
        }
        if (report.backups().isEmpty()) {
            result.append("\nยังไม่ได้กำหนด backup target สำหรับตรวจสอบครับ");
        }
        return result.toString().trim();
    }
}
