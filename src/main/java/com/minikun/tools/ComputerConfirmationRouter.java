package com.minikun.tools;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.planner.PlannerConfirmationService;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Executes one previously previewed local-computer operation after explicit confirmation. */
@Component
@Order(5)
@ConditionalOnProperty(name = "minikun.computer.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean({LocalComputerTool.class, PlannerConfirmationService.class})
public final class ComputerConfirmationRouter implements ToolRequestRouter {
    private static final String TOOL_NAME = "computer.local";
    private final ToolExecutor executor;
    private final PlannerConfirmationService confirmations;

    public ComputerConfirmationRouter(ToolExecutor executor, PlannerConfirmationService confirmations) {
        this.executor = Objects.requireNonNull(executor, "tool executor must not be null");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmation service must not be null");
    }

    @Override public Optional<ToolEvidence> route(String text, ConversationId conversationId) {
        return route(text, conversationId, "default");
    }

    @Override
    public Optional<ToolEvidence> route(String text, ConversationId conversationId, String ownerId) {
        if (!confirmation(text)) return Optional.empty();
        var pending = confirmations.find(conversationId, ownerId)
                .filter(value -> "computer.execute".equals(value.action()));
        if (pending.isEmpty()) return Optional.empty();
        String callId = "computer-confirm-" + UUID.randomUUID();
        Map<String, Object> arguments = confirmations.confirmedArguments(pending.get());
        ToolResult result = executor.execute(new ToolCallContext(conversationId, callId, ownerId),
                new ToolCall(callId, TOOL_NAME, arguments));
        if (!result.success()) return Optional.of(ToolEvidence.finalFailed(TOOL_NAME,
                "ขออภัยครับ การทำงานกับคอมพิวเตอร์ไม่สำเร็จ: " + result.error()));
        return Optional.of(ToolEvidence.finalVerified(TOOL_NAME,
                "ดำเนินการกับคอมพิวเตอร์ตามที่ยืนยันเรียบร้อยแล้วครับ\n" + result.value()));
    }

    private boolean confirmation(String text) {
        if (text == null || text.isBlank()) return false;
        String normalized = text.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[.!?,，。!?]+$", "").replaceAll("(ครับ|ค่ะ|คะ|นะ)$", "").trim();
        return switch (normalized) {
            case "ยืนยัน", "ใช่", "ตกลง", "ได้เลย", "ทำเลย", "confirm", "confirmed", "yes", "ok", "okay" -> true;
            default -> false;
        };
    }
}
