package com.minikun.tools;

import java.util.Map;

public interface Tool {
    ToolDefinition definition();

    ToolResult execute(ToolCallContext context, Map<String, Object> arguments);

    /** True when this invocation can persist data, affect the local system, or cause an external side effect. */
    default boolean requiresExplicitConfirmation(Map<String, Object> arguments) {
        // Fail closed: every new tool must explicitly opt into read-only behavior.
        return true;
    }
}
