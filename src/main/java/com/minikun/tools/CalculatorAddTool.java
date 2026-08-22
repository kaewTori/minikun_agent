package com.minikun.tools;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public final class CalculatorAddTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "calculator.add",
            "Add two numbers.",
            Map.of(
                    "a", new ToolParameter("a", ToolParameterType.NUMBER, true, "First number."),
                    "b", new ToolParameter("b", ToolParameterType.NUMBER, true, "Second number.")));

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override public boolean requiresExplicitConfirmation(Map<String, Object> arguments) { return false; }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        BigDecimal first = decimal(arguments.get("a"));
        BigDecimal second = decimal(arguments.get("b"));
        return ToolResult.success(first.add(second).stripTrailingZeros());
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }
}
