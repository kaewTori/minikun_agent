package com.minikun.tools;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.planner.PendingPlannerConfirmation;
import com.minikun.planner.PlannerConfirmationService;

/** Executes the exact pending planner proposal when the user confirms it. */
@Component
@ConditionalOnProperty(name = "minikun.planner.enabled", havingValue = "true", matchIfMissing = true)
public final class PlannerConfirmationRouter implements ToolRequestRouter {
    private static final String TOOL_NAME = "planner.manage";
    private static final java.util.Set<String> PLANNER_ACTIONS = java.util.Set.of(
            "create", "update", "cancel", "snooze");

    private final ToolExecutor executor;
    private final PlannerConfirmationService confirmations;

    public PlannerConfirmationRouter(ToolExecutor executor, PlannerConfirmationService confirmations) {
        this.executor = Objects.requireNonNull(executor, "tool executor must not be null");
        this.confirmations = Objects.requireNonNull(confirmations, "planner confirmations must not be null");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        if (!isConfirmation(userText)) {
            return Optional.empty();
        }
        Optional<PendingPlannerConfirmation> pending = confirmations.find(conversationId)
                .filter(value -> PLANNER_ACTIONS.contains(value.action()));
        if (pending.isEmpty()) {
            return Optional.empty();
        }

        String callId = "planner-confirm-" + UUID.randomUUID();
        ToolResult result = executor.executeAuthorized(
                new ToolCallContext(conversationId, callId),
                new ToolCall(callId, TOOL_NAME, confirmations.confirmedArguments(pending.get())));
        if (!result.success()) {
            return Optional.of(ToolEvidence.failed(TOOL_NAME,
                    "ขออภัยครับ มินิคุงบันทึกการแจ้งเตือนไม่สำเร็จ: " + result.error()));
        }
        confirmations.clear(conversationId);
        return Optional.of(ToolEvidence.verified(TOOL_NAME, formatSuccess(pending.get(), result)));
    }

    private boolean isConfirmation(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[.!?,，。!?]+$", "")
                .replaceAll("(ครับ|ค่ะ|คะ|นะ)$", "")
                .trim();
        return switch (normalized) {
            case "ยืนยัน", "ใช่", "ตกลง", "ได้เลย", "ทำเลย", "confirm", "confirmed", "yes", "ok", "okay" -> true;
            default -> false;
        };
    }

    private String formatSuccess(PendingPlannerConfirmation pending, ToolResult result) {
        Object title = pending.arguments().get("title");
        String titleText = title == null || title.toString().isBlank() ? "รายการที่ขอ" : title.toString();
        return "ยืนยันแล้วครับ ระบบบันทึกการแจ้งเตือนเรียบร้อยแล้ว\n"
                + "รายการ: " + titleText + "\nผลลัพธ์ที่ตรวจสอบแล้ว: " + result.value();
    }
}
