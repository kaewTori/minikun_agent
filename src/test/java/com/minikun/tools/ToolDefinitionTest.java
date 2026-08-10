package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ToolDefinitionTest {
    @Test
    void acceptsValidDefinitionAndOrdersParameters() {
        ToolDefinition definition = new ToolDefinition(
                "calculator.add",
                "Add two numbers.",
                Map.of(
                        "b", new ToolParameter("b", ToolParameterType.NUMBER, true, "Second number."),
                        "a", new ToolParameter("a", ToolParameterType.NUMBER, true, "First number.")));

        assertEquals("[a, b]", definition.parameters().keySet().toString());
    }

    @Test
    void rejectsBlankNameAndDescription() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDefinition(" ", "description", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDefinition("name", " ", Map.of()));
    }

    @Test
    void parametersAreImmutable() {
        Map<String, ToolParameter> parameters = new HashMap<>();
        parameters.put("a", new ToolParameter("a", ToolParameterType.NUMBER, true, "First number."));
        ToolDefinition definition = new ToolDefinition("tool", "A tool.", parameters);

        parameters.put("b", new ToolParameter("b", ToolParameterType.STRING, false, "Extra."));

        assertEquals(1, definition.parameters().size());
        assertThrows(UnsupportedOperationException.class,
                () -> definition.parameters().put("b", parameters.get("b")));
    }
}