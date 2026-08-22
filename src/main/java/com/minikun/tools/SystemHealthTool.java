package com.minikun.tools;

import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.minikun.systemhealth.SystemHealthReader;

/** Read-only host health tool backed by Java/JMX system probes. */
@Component
public final class SystemHealthTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "system.health",
            "Inspect the host running Mini-kun. Use this when the user asks about the machine itself, "
                    + "including CPU, RAM, swap, disk, JVM, process, or local dependency ports. "
                    + "This is read-only and returns verified status; do not invent values or run shell commands.",
            Map.of());

    private final SystemHealthReader reader;

    public SystemHealthTool(SystemHealthReader reader) {
        this.reader = Objects.requireNonNull(reader, "system health reader must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override public boolean requiresExplicitConfirmation(Map<String, Object> arguments) { return false; }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        try {
            return ToolResult.success(reader.read());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "system health is temporarily unavailable");
        }
    }
}
