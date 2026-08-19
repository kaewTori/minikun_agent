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

/** Replays a pending task write after an explicit confirmation message. */
@Component
@ConditionalOnProperty(name = "minikun.task.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(TaskManageTool.class)
public final class TaskConfirmationRouter implements ToolRequestRouter {
    private static final String TOOL_NAME = "task.manage";
    private final ToolExecutor executor;
    private final PlannerConfirmationService confirmations;

    public TaskConfirmationRouter(ToolExecutor executor, PlannerConfirmationService confirmations) {
        this.executor = Objects.requireNonNull(executor);
        this.confirmations = Objects.requireNonNull(confirmations);
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        return route(userText, conversationId, "default");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId) {
        if (!isConfirmation(userText)) return Optional.empty();
        Optional<PendingPlannerConfirmation> pending = confirmations.find(conversationId, ownerId)
                .filter(value -> value.action().startsWith("task."));
        if (pending.isEmpty()) return Optional.empty();
        String callId = "task-confirm-" + UUID.randomUUID();
        ToolResult result = executor.execute(new ToolCallContext(conversationId, callId, ownerId),
                new ToolCall(callId, TOOL_NAME, confirmations.confirmedArguments(pending.get())));
        confirmations.clear(conversationId);
        if (!result.success()) {
            return Optional.of(ToolEvidence.failed(TOOL_NAME, "ขออภัยครับ ดำเนินการกับงานไม่สำเร็จ: " + result.error()));
        }
        return Optional.of(ToolEvidence.verified(TOOL_NAME, "ยืนยันแล้วครับ งานถูกอัปเดตเรียบร้อยแล้ว\n" + result.value()));
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
