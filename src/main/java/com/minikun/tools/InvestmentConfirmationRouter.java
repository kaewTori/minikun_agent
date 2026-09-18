package com.minikun.tools;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.planner.PendingPlannerConfirmation;
import com.minikun.planner.PlannerConfirmationService;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Replays the exact pending investment proposal after explicit owner confirmation. */
@Component
@ConditionalOnProperty(name = "minikun.investment.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(InvestmentManageTool.class)
public final class InvestmentConfirmationRouter implements ToolRequestRouter {
    private static final String TOOL_NAME = "investment.manage";
    private final ToolExecutor executor;
    private final PlannerConfirmationService confirmations;

    public InvestmentConfirmationRouter(ToolExecutor executor, PlannerConfirmationService confirmations) {
        this.executor = Objects.requireNonNull(executor, "tool executor must not be null");
        this.confirmations = Objects.requireNonNull(confirmations, "investment confirmations must not be null");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        return route(userText, conversationId, "default");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId) {
        if (!isConfirmation(userText)) return Optional.empty();
        Optional<PendingPlannerConfirmation> pending = confirmations.find(conversationId, ownerId)
                .filter(value -> value.action().startsWith("investment."));
        if (pending.isEmpty()) return Optional.empty();
        String callId = "investment-confirm-" + UUID.randomUUID();
        ToolResult result = executor.execute(
                new ToolCallContext(conversationId, callId, ownerId),
                new ToolCall(callId, TOOL_NAME, confirmations.confirmedArguments(pending.get())));
        if (!result.success()) {
            return Optional.of(ToolEvidence.finalFailed(TOOL_NAME,
                    "ขออภัยครับ ดำเนินการกับข้อมูลการลงทุนไม่สำเร็จ: " + result.error()));
        }
        confirmations.clear(conversationId);
        return Optional.of(ToolEvidence.finalVerified(TOOL_NAME,
                "ยืนยันแล้วครับ ข้อมูลการลงทุนถูกอัปเดตเรียบร้อยแล้ว\n" + result.value()));
    }

    private boolean isConfirmation(String text) {
        if (text == null || text.isBlank()) return false;
        String normalized = text.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[.!?,，。!?]+$", "")
                .replaceAll("(ครับ|ค่ะ|คะ|นะ)$", "").trim();
        return switch (normalized) {
            case "ยืนยัน", "ใช่", "ตกลง", "ได้เลย", "ทำเลย", "confirm", "confirmed", "yes", "ok", "okay" -> true;
            default -> false;
        };
    }
}
