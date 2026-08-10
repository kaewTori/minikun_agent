package com.minikun.tools.springai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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

public final class SpringAiToolCallback implements ToolCallback {
    private final Tool tool;
    private final ToolExecutor executor;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;
    private final ThreadLocal<String> currentCallId = new ThreadLocal<>();

    public SpringAiToolCallback(Tool tool, ToolExecutor executor, ObjectMapper objectMapper) {
        this.tool = Objects.requireNonNull(tool, "tool must not be null");
        this.executor = Objects.requireNonNull(executor, "tool executor must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
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
            Map<String, Object> arguments = objectMapper.convertValue(
                    objectMapper.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput), Map.class);
            String conversationValue = value(toolContext, "conversationId", "tool-call");
            ToolResult result = executor.execute(
                    new ToolCallContext(new ConversationId(conversationValue), callId()),
                    new ToolCall(callId(), tool.definition().name(), arguments));
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return errorResult(ToolErrorCode.INVALID_ARGUMENTS, "invalid tool arguments");
        }
    }

    void setCurrentCallId(String callId) {
        currentCallId.set(callId);
    }

    void clearCurrentCallId() {
        currentCallId.remove();
    }

    private String callId() {
        return Objects.requireNonNullElse(currentCallId.get(), "spring-ai-tool-call");
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

    private String errorResult(ToolErrorCode errorCode, String message) {
        try {
            return objectMapper.writeValueAsString(ToolResult.failure(errorCode, message));
        } catch (JsonProcessingException exception) {
            return "{\"success\":false,\"errorCode\":\"INVALID_ARGUMENTS\",\"error\":\"invalid tool arguments\"}";
        }
    }
}