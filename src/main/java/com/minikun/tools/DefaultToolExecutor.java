package com.minikun.tools;

import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public final class DefaultToolExecutor implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(DefaultToolExecutor.class);

    private final ToolRegistry registry;

    public DefaultToolExecutor(ToolRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "tool registry must not be null");
    }

    @Override
    public ToolResult execute(ToolCallContext context, ToolCall toolCall) {
        Objects.requireNonNull(context, "tool call context must not be null");
        Objects.requireNonNull(toolCall, "tool call must not be null");
        long started = System.nanoTime();
        ToolResult result;
        try {
            result = registry.find(toolCall.name())
                    .map(tool -> executeRegisteredTool(tool, context, toolCall))
                    .orElseGet(() -> ToolResult.failure(
                            ToolErrorCode.TOOL_NOT_FOUND, "tool not found: " + toolCall.name()));
        } catch (RuntimeException exception) {
            result = ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "tool execution failed");
            log.warn("Tool execution raised an exception tool={} callId={}", toolCall.name(), toolCall.id(), exception);
        }
        log.info("process=tool_execution tool={} call_id={} success={} duration_ms={}",
                toolCall.name(), toolCall.id(), result.success(), (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    private ToolResult executeRegisteredTool(Tool tool, ToolCallContext context, ToolCall toolCall) {
        ToolResult validation = validate(tool.definition(), toolCall.arguments());
        if (validation != null) return validation;
        ToolResult blocked = BackgroundToolScope.guard(tool.requiresExplicitConfirmation(toolCall.arguments()));
        return blocked == null ? tool.execute(context, toolCall.arguments()) : blocked;
    }

    private ToolResult validate(ToolDefinition definition, Map<String, Object> arguments) {
        for (String argumentName : arguments.keySet()) {
            if (!definition.parameters().containsKey(argumentName)) {
                return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                        "unknown argument: " + argumentName);
            }
        }
        for (ToolParameter parameter : definition.parameters().values()) {
            Object value = arguments.get(parameter.name());
            if (parameter.required() && value == null) {
                return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                        "missing required argument: " + parameter.name());
            }
            if (value != null && !matches(parameter.type(), value)) {
                return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                        "invalid argument type: " + parameter.name());
            }
        }
        return null;
    }

    private boolean matches(ToolParameterType type, Object value) {
        return switch (type) {
            case STRING -> value instanceof String;
            case NUMBER -> value instanceof Number;
            case INTEGER -> value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long;
            case BOOLEAN -> value instanceof Boolean;
        };
    }
}
