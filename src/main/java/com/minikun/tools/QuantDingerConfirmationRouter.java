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

/** Replays a pending QuantDinger strategy write only after owner confirmation. */
@Component
@ConditionalOnProperty(name = "minikun.investment.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(QuantDingerTool.class)
public final class QuantDingerConfirmationRouter implements ToolRequestRouter {
    private final ToolExecutor executor;
    private final PlannerConfirmationService confirmations;

    public QuantDingerConfirmationRouter(ToolExecutor executor, PlannerConfirmationService confirmations) {
        this.executor = Objects.requireNonNull(executor, "tool executor must not be null");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmation service must not be null");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        return route(userText, conversationId, "default");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId) {
        if (!confirmation(userText)) return Optional.empty();
        Optional<PendingPlannerConfirmation> pending = confirmations.find(conversationId, ownerId)
                .filter(value -> value.action().equals("quantdinger.save_strategy"));
        if (pending.isEmpty()) return Optional.empty();
        String callId = "quantdinger-confirm-" + UUID.randomUUID();
        ToolResult result = executor.executeAuthorized(new ToolCallContext(conversationId, callId, ownerId),
                new ToolCall(callId, "investment.quantdinger", confirmations.confirmedArguments(pending.get())));
        if (!result.success()) {
            return Optional.of(ToolEvidence.failed("investment.quantdinger",
                    "ขออภัยครับ บันทึก Strategy ไม่สำเร็จ: " + result.error()));
        }
        confirmations.clear(conversationId);
        return Optional.of(ToolEvidence.verified("investment.quantdinger",
                "ยืนยันแล้วครับ Strategy ถูกบันทึกเป็น version แล้ว\n" + result.value()));
    }

    private boolean confirmation(String text) {
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
