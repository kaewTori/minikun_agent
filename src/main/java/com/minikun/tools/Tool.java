package com.minikun.tools;

import java.util.Map;

public interface Tool {
    ToolDefinition definition();

    ToolResult execute(ToolCallContext context, Map<String, Object> arguments);
}