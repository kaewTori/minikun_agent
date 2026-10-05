package com.minikun.tools;

public interface ToolExecutor {
    ToolResult execute(ToolCallContext context, ToolCall toolCall);

    /** Called only by server confirmation routers or a policy-authorized action worker. */
    default ToolResult executeAuthorized(ToolCallContext context, ToolCall toolCall) {
        try (var consent = new ToolAuthorizationScope(context, toolCall)) {
            return execute(context, toolCall);
        }
    }
}
