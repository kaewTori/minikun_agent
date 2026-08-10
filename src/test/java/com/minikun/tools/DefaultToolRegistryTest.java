package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DefaultToolRegistryTest {
    @Test
    void registersAndFindsToolsInDeterministicOrder() {
        Tool first = tool("zeta");
        Tool second = tool("alpha");
        DefaultToolRegistry registry = new DefaultToolRegistry(List.of(first, second));

        assertEquals(second, registry.find("alpha").orElseThrow());
        assertEquals(List.of("alpha", "zeta"), registry.definitions().stream().map(ToolDefinition::name).toList());
        assertTrue(registry.find("missing").isEmpty());
    }

    @Test
    void rejectsDuplicateToolNames() {
        assertThrows(IllegalArgumentException.class,
                () -> new DefaultToolRegistry(List.of(tool("same"), tool("same"))));
    }

    private Tool tool(String name) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "Description.", Map.of());
            }

            @Override
            public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
                return ToolResult.success("ok");
            }
        };
    }
}