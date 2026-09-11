package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import org.junit.jupiter.api.Test;

class BackgroundToolScopeTest {
    @Test
    void recoveryCannotCreateANewGenericRiskConfirmationBeforeExecutorGuard() {
        Tool writer = new Tool() {
            public ToolDefinition definition() { return new ToolDefinition("write", "write", Map.of()); }
            public ToolResult execute(ToolCallContext context, Map<String, Object> args) { throw new AssertionError("write"); }
        };
        var confirmations = org.mockito.Mockito.mock(com.minikun.planner.PlannerConfirmationService.class);
        var executor = org.mockito.Mockito.mock(ToolExecutor.class);
        var callback = new com.minikun.tools.springai.SpringAiToolCallback(writer, executor,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                com.minikun.agent.execution.AgentExecutionTracker.noop(), confirmations);
        try (var scope = new BackgroundToolScope(UUID.randomUUID(), true)) {
            String result = callback.call("{}", new org.springframework.ai.chat.model.ToolContext(Map.of("riskExplicitReview", true)));
            assertTrue(result.contains("REVIEW_REQUIRED"));
            assertTrue(scope.blocked());
        }
        org.mockito.Mockito.verifyNoInteractions(confirmations, executor);
    }

    @Test
    void sameOperationCannotRepeatAWriteAfterRecoveryButReadOnlyToolsStillWork() {
        AtomicInteger writes = new AtomicInteger();
        Tool writer = new Tool() {
            public ToolDefinition definition() { return new ToolDefinition("write", "write", Map.of()); }
            public ToolResult execute(ToolCallContext context, Map<String, Object> args) {
                writes.incrementAndGet();
                return ToolResult.success(Map.of("saved", true));
            }
        };
        var executor = new DefaultToolExecutor(new DefaultToolRegistry(List.of(writer, new CalculatorAddTool())));
        var context = new ToolCallContext(new ConversationId("recovery"), "call", "owner");
        UUID operation = UUID.randomUUID();
        try (var scope = new BackgroundToolScope(operation, false)) {
            assertTrue(executor.execute(context, new ToolCall("call", "write", Map.of())).success());
        }
        try (var scope = new BackgroundToolScope(operation, true)) {
            assertEquals(ToolErrorCode.REVIEW_REQUIRED,
                    executor.execute(context, new ToolCall("new-model-call-id", "write", Map.of())).errorCode());
            assertTrue(scope.blocked());
            assertTrue(executor.execute(context, new ToolCall("read", "calculator.add", Map.of("a", 2, "b", 3))).success());
        }
        assertEquals(1, writes.get());
    }

    @Test
    void cancellationBlocksLateToolCallsAndScopeDoesNotLeak() {
        try (var scope = new BackgroundToolScope(UUID.randomUUID(), false)) {
            Thread.currentThread().interrupt();
            assertEquals(ToolErrorCode.REVIEW_REQUIRED, BackgroundToolScope.guard(true).errorCode());
            assertTrue(scope.blocked());
        } finally {
            Thread.interrupted();
        }
        assertNull(BackgroundToolScope.guard(true));
    }
}
