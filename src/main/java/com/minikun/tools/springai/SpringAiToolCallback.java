package com.minikun.tools.springai;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.tools.Tool;
import com.minikun.tools.ToolCall;
import com.minikun.tools.ToolCallContext;
import com.minikun.tools.ToolErrorCode;
import com.minikun.tools.ToolExecutor;
import com.minikun.tools.ToolParameter;
import com.minikun.tools.ToolParameterType;
import com.minikun.tools.ToolResult;
import com.minikun.agent.execution.AgentExecutionStep;
import com.minikun.agent.execution.AgentExecutionTracker;
import com.minikun.planner.PlannerConfirmationService;

public final class SpringAiToolCallback implements ToolCallback {
    private final Tool tool;
    private final ToolExecutor executor;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;
    private final AgentExecutionTracker executionTracker;
    private final PlannerConfirmationService confirmations;
    private final ThreadLocal<Deque<String>> currentCallIds = new ThreadLocal<>();

    public SpringAiToolCallback(Tool tool, ToolExecutor executor, ObjectMapper objectMapper) {
        this(tool, executor, objectMapper, AgentExecutionTracker.noop(), null);
    }

    public SpringAiToolCallback(Tool tool, ToolExecutor executor, ObjectMapper objectMapper,
            AgentExecutionTracker executionTracker) {
        this(tool, executor, objectMapper, executionTracker, null);
    }

    public SpringAiToolCallback(Tool tool, ToolExecutor executor, ObjectMapper objectMapper,
            AgentExecutionTracker executionTracker, PlannerConfirmationService confirmations) {
        this.tool = Objects.requireNonNull(tool, "tool must not be null");
        this.executor = Objects.requireNonNull(executor, "tool executor must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.executionTracker = Objects.requireNonNull(executionTracker, "execution tracker must not be null");
        this.confirmations = confirmations;
        this.definition = ToolDefinition.builder()
                .name(tool.definition().name())
                .description(tool.definition().description())
                .inputSchema(inputSchema(tool))
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try {
            Map<String, Object> arguments = new LinkedHashMap<>(objectMapper.convertValue(
                    objectMapper.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput), Map.class));
            String conversationValue = value(toolContext, "conversationId", "tool-call");
            String ownerValue = value(toolContext, "ownerId", "default");
            String toolCallId = callId();
            Optional<UUID> runId = uuid(toolContext, "agentRunId");
            boolean requiresRiskReview = booleanValue(toolContext, "riskExplicitReview")
                    && tool.requiresExplicitConfirmation(arguments);
            boolean genericRiskConfirmation = requiresRiskReview
                    && !tool.definition().parameters().containsKey("confirmed");
            if (requiresRiskReview && !genericRiskConfirmation) {
                // A model cannot self-approve a high-risk action in the proposal turn.
                arguments.put("confirmed", false);
            }
            AgentExecutionStep step = runId.map(id -> executionTracker.beginStep(
                    id, toolCallId, tool.definition().name(), arguments)).orElse(null);
            ToolResult result;
            if (genericRiskConfirmation) {
                result = createRiskConfirmation(conversationValue, ownerValue, toolCallId, runId, arguments);
                if (runId.isPresent()) executionTracker.finishStep(runId.get(), toolCallId, result, false);
                return objectMapper.writeValueAsString(modelFacingResult(result));
            }
            while (true) {
                result = executor.execute(
                        new ToolCallContext(new ConversationId(conversationValue), toolCallId, ownerValue),
                        new ToolCall(toolCallId, tool.definition().name(), arguments));
                boolean retry = step != null && executionTracker.shouldRetry(result, step.attempts());
                if (runId.isPresent()) executionTracker.finishStep(runId.get(), toolCallId, result, retry);
                if (!retry) break;
                step = executionTracker.beginStep(runId.get(), toolCallId, tool.definition().name(), arguments);
            }
            return objectMapper.writeValueAsString(modelFacingResult(result));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return errorResult(ToolErrorCode.INVALID_ARGUMENTS, "invalid tool arguments");
        }
    }

    void setCurrentCallId(String callId) {
        setCurrentCallIds(List.of(callId));
    }

    void setCurrentCallIds(List<String> callIds) {
        currentCallIds.set(new ArrayDeque<>(callIds));
    }

    void clearCurrentCallId() {
        currentCallIds.remove();
    }

    private String callId() {
        Deque<String> callIds = currentCallIds.get();
        if (callIds != null && !callIds.isEmpty()) {
            return callIds.removeFirst();
        }
        return "spring-ai-tool-call";
    }

    private String inputSchema(Tool tool) {
        Map<String, Object> properties = new LinkedHashMap<>();
        java.util.List<String> required = new java.util.ArrayList<>();
        for (ToolParameter parameter : tool.definition().parameters().values()) {
            properties.put(parameter.name(), Map.of(
                    "type", jsonType(parameter.type()),
                    "description", parameter.description()));
            if (parameter.required()) {
                required.add(parameter.name());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        try {
            return objectMapper.writeValueAsString(schema);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize tool schema", exception);
        }
    }

    private String jsonType(ToolParameterType type) {
        return switch (type) {
            case STRING -> "string";
            case NUMBER -> "number";
            case INTEGER -> "integer";
            case BOOLEAN -> "boolean";
        };
    }

    private String value(ToolContext context, String key, String fallback) {
        if (context == null || context.getContext().get(key) == null) {
            return fallback;
        }
        return context.getContext().get(key).toString();
    }

    private Optional<UUID> uuid(ToolContext context, String key) {
        String value = value(context, key, "");
        if (value.isBlank()) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private boolean booleanValue(ToolContext context, String key) {
        String value = value(context, key, "false");
        return "true".equalsIgnoreCase(value);
    }

    private ToolResult createRiskConfirmation(String conversationId, String ownerId, String toolCallId,
            Optional<UUID> runId, Map<String, Object> arguments) {
        if (confirmations == null) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "risk confirmation storage is unavailable; no action was performed");
        }
        Map<String, Object> pending = new LinkedHashMap<>(arguments);
        pending.put("_risk_tool_call_id", toolCallId);
        runId.ifPresent(id -> pending.put("_risk_agent_run_id", id.toString()));
        confirmations.save(new ConversationId(conversationId), ownerId,
                "agent-risk." + tool.definition().name(), pending);
        return ToolResult.success(Map.of(
                "requires_confirmation", true,
                "message", "แผนนี้มีการเปลี่ยนแปลงที่มีความเสี่ยงสูงและยังไม่ได้ดำเนินการครับ กรุณายืนยันก่อน",
                "tool", tool.definition().name()));
    }

    private String errorResult(ToolErrorCode errorCode, String message) {
        try {
            return objectMapper.writeValueAsString(modelFacingResult(ToolResult.failure(errorCode, message)));
        } catch (JsonProcessingException exception) {
            return "{\"success\":false,\"tool\":\"unknown\","
                    + "\"assistant_instruction\":\"The tool failed. Explain the failure honestly in the MCS style.\"}";
        }
    }

    /**
     * Keep the domain result structured, but make the continuation contract explicit
     * for smaller local models. The original nested {@code ToolResult} shape left the
     * model to infer that a successful value was authoritative evidence.
     */
    private Map<String, Object> modelFacingResult(ToolResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", result.success());
        response.put("tool", tool.definition().name());
        if (result.success()) {
            response.put("result", result.value());
            if (requiresConfirmation(result.value())) {
                response.put("assistant_instruction",
                        "The requested write operation has not been applied yet. Ask the user to confirm the "
                                + "proposed change in the identity, language, tone, and response style from MCS. "
                                + "Do not claim that the event or reminder was saved.");
            } else {
                response.put("assistant_instruction",
                        "This is a verified result from the tool. Answer the user's original request now using "
                                + "these facts. Do not claim that the tool or external data is unavailable, do not "
                                + "invent missing values, and preserve the identity, language, tone, and response "
                                + "style from MCS.");
            }
        } else {
            response.put("error_code", result.errorCode());
            response.put("error", result.error());
            response.put("assistant_instruction",
                    "The tool failed. Explain the failure honestly in the identity, language, tone, and response "
                            + "style from MCS, and do not fabricate an answer.");
        }
        return response;
    }

    private boolean requiresConfirmation(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return false;
        }
        Object flag = map.get("requires_confirmation");
        return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
    }
}
