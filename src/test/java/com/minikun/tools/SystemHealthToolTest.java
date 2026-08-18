package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.systemhealth.SystemHealthReader;
import com.minikun.systemhealth.SystemHealthReport;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SystemHealthToolTest {
    @Test
    void returnsVerifiedHostSnapshot() {
        SystemHealthReader reader = () -> new SystemHealthReport(
                "UP", true,
                Map.of("status", "UP"),
                Map.of("status", "UP"),
                Map.of("status", "UP"),
                Map.of("status", "UP"),
                Map.of("status", "UP"),
                Map.of("status", "RUNNING"),
                Map.of("postgres", Map.of("status", "UP")));

        ToolResult result = new SystemHealthTool(reader).execute(null, Map.of());

        assertTrue(result.success());
        assertEquals("UP", ((SystemHealthReport) result.value()).status());
        assertTrue(((SystemHealthReport) result.value()).healthy());
    }

    @Test
    void hidesProbeFailureDetails() {
        SystemHealthTool tool = new SystemHealthTool(() -> {
            throw new IllegalStateException("/secret/path");
        });

        ToolResult result = tool.execute(null, Map.of());

        assertFalse(result.success());
        assertEquals(ToolErrorCode.EXECUTION_FAILED, result.errorCode());
        assertEquals("system health is temporarily unavailable", result.error());
    }
}
