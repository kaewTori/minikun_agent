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

/** Replays exactly one pending allowlisted guardian action after explicit confirmation. */
@Component
@ConditionalOnProperty(name = "minikun.guardian.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean({HomelabGuardianTool.class, PlannerConfirmationService.class})
public final class GuardianConfirmationRouter implements ToolRequestRouter {
    private static final String TOOL_NAME = "homelab.guardian";
    private final ToolExecutor executor;
    private final PlannerConfirmationService confirmations;

    public GuardianConfirmationRouter(ToolExecutor executor, PlannerConfirmationService confirmations) {
        this.executor = Objects.requireNonNull(executor, "tool executor must not be null");
        this.confirmations = Objects.requireNonNull(confirmations, "guardian confirmations must not be null");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        return route(userText, conversationId, "default");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId) {
        if (!isConfirmation(userText)) return Optional.empty();
        Optional<PendingPlannerConfirmation> pending = confirmations.find(conversationId, ownerId)
                .filter(value -> "guardian.execute".equals(value.action()));
        if (pending.isEmpty()) return Optional.empty();
        String callId = "guardian-confirm-" + UUID.randomUUID();
        ToolResult result = executor.executeAuthorized(new ToolCallContext(conversationId, callId, ownerId),
                new ToolCall(callId, TOOL_NAME, confirmations.confirmedArguments(pending.get())));
        confirmations.clear(conversationId);
        if (!result.success()) {
            return Optional.of(ToolEvidence.finalFailed(TOOL_NAME,
                    "ขออภัยครับ guardian action ไม่สำเร็จ: " + result.error()));
        }
        return Optional.of(ToolEvidence.finalVerified(TOOL_NAME,
                "ยืนยันแล้วครับ guardian action ดำเนินการเสร็จแล้ว\n" + result.value()));
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
