package com.minikun.tools;

import com.minikun.agent.execution.AgentExecutionService;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.planner.PendingPlannerConfirmation;
import com.minikun.planner.PlannerConfirmationService;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** Executes a high-risk tool without a native confirmation contract only after an explicit user approval. */
@Component
@ConditionalOnBean(PlannerConfirmationService.class)
public final class AgentRiskConfirmationRouter implements ToolRequestRouter {
    private static final String ACTION_PREFIX = "agent-risk.";

    private final ToolExecutor executor;
    private final ToolRegistry registry;
    private final PlannerConfirmationService confirmations;
    private final AgentExecutionService executions;

    public AgentRiskConfirmationRouter(ToolExecutor executor, ToolRegistry registry,
            PlannerConfirmationService confirmations, ObjectProvider<AgentExecutionService> executions) {
        this.executor = Objects.requireNonNull(executor);
        this.registry = Objects.requireNonNull(registry);
        this.confirmations = Objects.requireNonNull(confirmations);
        this.executions = executions == null ? null : executions.getIfAvailable();
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        return route(userText, conversationId, "default");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId) {
        if (!isConfirmation(userText)) return Optional.empty();
        Optional<PendingPlannerConfirmation> pending = confirmations.find(conversationId, ownerId)
                .filter(value -> value.action().startsWith(ACTION_PREFIX));
        if (pending.isEmpty()) return Optional.empty();

        String toolName = pending.get().action().substring(ACTION_PREFIX.length());
        Tool tool = registry.find(toolName).orElse(null);
        if (tool == null) {
            confirmations.clear(conversationId);
            return Optional.of(ToolEvidence.failed(toolName, "ไม่พบเครื่องมือสำหรับรายการที่รอยืนยันครับ"));
        }

        Map<String, Object> arguments = new LinkedHashMap<>(pending.get().arguments());
        Optional<UUID> runId = removeUuid(arguments, "_risk_agent_run_id");
        String callId = Objects.toString(arguments.remove("_risk_tool_call_id"),
                "agent-risk-confirm-" + UUID.randomUUID());
        if (!tool.requiresExplicitConfirmation(arguments)
                || tool.definition().parameters().containsKey("confirmed")) {
            confirmations.clear(conversationId);
            return Optional.of(ToolEvidence.failed(toolName, "รายการที่รอยืนยันไม่ผ่านนโยบายความปลอดภัยครับ"));
        }

        ToolResult result = executeTracked(runId, conversationId, ownerId, callId, toolName, arguments);
        if (!result.success()) {
            return Optional.of(ToolEvidence.failed(toolName,
                    "ขออภัยครับ ดำเนินการหลังการยืนยันไม่สำเร็จ: " + result.error()));
        }
        confirmations.clear(conversationId);
        return Optional.of(ToolEvidence.verified(toolName,
                "ยืนยันแล้วครับ ดำเนินการเรียบร้อยแล้ว\n" + result.value()));
    }

    private ToolResult executeTracked(Optional<UUID> runId, ConversationId conversationId, String ownerId,
            String callId, String toolName, Map<String, Object> arguments) {
        if (runId.isEmpty() || executions == null) {
            return executor.executeAuthorized(new ToolCallContext(conversationId, callId, ownerId),
                    new ToolCall(callId, toolName, arguments));
        }
        executions.beginStep(runId.get(), callId, toolName, arguments);
        ToolResult result = executor.executeAuthorized(new ToolCallContext(conversationId, callId, ownerId),
                new ToolCall(callId, toolName, arguments));
        // A confirmed write may have taken effect even when its response failed.
        executions.finishStep(runId.get(), callId, result, false);
        if (result.success()) executions.markResumed(runId.get(), "completed after explicit risk review");
        else executions.fail(runId.get(), result.error());
        return result;
    }

    private Optional<UUID> removeUuid(Map<String, Object> arguments, String key) {
        Object value = arguments.remove(key);
        if (value == null) return Optional.empty();
        try { return Optional.of(UUID.fromString(value.toString())); }
        catch (IllegalArgumentException exception) { return Optional.empty(); }
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
