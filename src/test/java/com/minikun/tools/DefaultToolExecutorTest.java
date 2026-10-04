package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.conversation.ConversationId;

class DefaultToolExecutorTest {
    private static final ToolCallContext CONTEXT = new ToolCallContext(
            new ConversationId("conversation"), "call-1");

    @Test
    void executesValidToolCall() {
        Tool tool = tool("calculator", new ToolDefinition(
                "calculator", "Calculate.", Map.of(
                        "a", new ToolParameter("a", ToolParameterType.NUMBER, true, "First."))),
                (context, arguments) -> ToolResult.success(arguments.get("a")));

        ToolResult result = executor(tool).execute(CONTEXT, new ToolCall("call-1", "calculator", Map.of("a", 2)));

        assertEquals(ToolResult.success(2), result);
    }

    @Test
    void acceptsNestedJsonObjectsForObjectParameters() {
        Map<String, Object> spec = Map.of("title", "Deck", "slides", List.of(Map.of("title", "One")));
        Tool tool = tool("presentation.create", new ToolDefinition("presentation.create", "Create a deck.",
                Map.of("spec", new ToolParameter("spec", ToolParameterType.OBJECT, true, "Nested deck spec."))),
                (context, arguments) -> ToolResult.success(arguments.get("spec")));

        ToolResult result = executor(tool).execute(CONTEXT,
                new ToolCall("call-1", "presentation.create", Map.of("spec", spec)));

        assertEquals(ToolResult.success(spec), result);
    }

    @Test
    void returnsStableValidationFailures() {
        Tool tool = tool("calculator", new ToolDefinition(
                "calculator", "Calculate.", Map.of(
                        "a", new ToolParameter("a", ToolParameterType.NUMBER, true, "First."))),
                (context, arguments) -> ToolResult.success(arguments.get("a")));
        DefaultToolExecutor executor = executor(tool);

        assertEquals(ToolErrorCode.TOOL_NOT_FOUND,
                executor.execute(CONTEXT, new ToolCall("call-1", "missing", Map.of())).errorCode());
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS,
                executor.execute(CONTEXT, new ToolCall("call-1", "calculator", Map.of())).errorCode());
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS,
                executor.execute(CONTEXT, new ToolCall("call-1", "calculator", Map.of("a", "wrong"))).errorCode());
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS,
                executor.execute(CONTEXT, new ToolCall("call-1", "calculator", Map.of("a", 2, "extra", true)))
                        .errorCode());
    }

    @Test
    void convertsToolFailureToExecutionFailure() {
        Tool tool = tool("failing", new ToolDefinition("failing", "Fails.", Map.of()),
                (context, arguments) -> {
                    throw new IllegalStateException("internal detail");
                });

        ToolResult result = executor(tool).execute(CONTEXT, new ToolCall("call-1", "failing", Map.of()));

        assertEquals(ToolErrorCode.EXECUTION_FAILED, result.errorCode());
        assertEquals("tool execution failed", result.error());
    }

    @Test
    void calculatorAddsNumbers() {
        ToolResult result = executor(new CalculatorAddTool()).execute(
                CONTEXT, new ToolCall("call-1", "calculator.add", Map.of("a", 2, "b", 3)));

        assertEquals("5", result.value().toString());
    }

    private DefaultToolExecutor executor(Tool tool) {
        return new DefaultToolExecutor(new DefaultToolRegistry(List.of(tool)));
    }

    private Tool tool(String name, ToolDefinition definition, ToolExecution execution) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
                return execution.execute(context, arguments);
            }
        };
    }

    @FunctionalInterface
    private interface ToolExecution {
        ToolResult execute(ToolCallContext context, Map<String, Object> arguments);
    }
}
