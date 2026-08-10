package com.minikun.tools;

public interface ToolExecutor {
    ToolResult execute(ToolCallContext context, ToolCall toolCall);
}