package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minikun.agent.execution.AgentExecutionService;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.planner.PendingPlannerConfirmation;
import com.minikun.planner.PlannerConfirmationService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AgentRiskConfirmationRouterTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void executesGenericHighRiskProposalOnceEvenWhenTheOutcomeFails(boolean succeeds) {
        ToolExecutor executor = mock(ToolExecutor.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        PlannerConfirmationService confirmations = mock(PlannerConfirmationService.class);
        ObjectProvider<AgentExecutionService> executions = mock(ObjectProvider.class);
        Tool tool = new Tool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("memory.write", "persist a value", Map.of(
                        "value", new ToolParameter("value", ToolParameterType.STRING, true, "value")));
            }
            @Override public boolean requiresExplicitConfirmation(Map<String, Object> arguments) { return true; }
            @Override public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
                return ToolResult.success(arguments);
            }
        };
        ConversationId conversationId = new ConversationId("conversation");
        Instant now = Instant.parse("2026-08-22T00:00:00Z");
        PendingPlannerConfirmation pending = new PendingPlannerConfirmation(
                conversationId.value(), "owner", "agent-risk.memory.write",
                Map.of("value", "approved", "_risk_tool_call_id", "call-1",
                        "_risk_agent_run_id", "00000000-0000-0000-0000-000000000001"), now, now.plusSeconds(60));
        var tracker = mock(AgentExecutionService.class);
        when(executions.getIfAvailable()).thenReturn(tracker);
        when(confirmations.find(conversationId, "owner")).thenReturn(Optional.of(pending));
        when(registry.find("memory.write")).thenReturn(Optional.of(tool));
        when(executor.execute(any(), any())).thenReturn(succeeds ? ToolResult.success(Map.of("saved", true))
                : ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "response lost after write"));
        AgentRiskConfirmationRouter router = new AgentRiskConfirmationRouter(
                executor, registry, confirmations, executions);

        Optional<ToolEvidence> result = router.route("ยืนยัน", conversationId, "owner");

        assertTrue(result.isPresent());
        org.junit.jupiter.api.Assertions.assertEquals(succeeds, result.get().success());
        verify(executor).execute(any(), any());
        if (succeeds) verify(confirmations).clear(conversationId);
        verify(tracker, org.mockito.Mockito.never()).shouldRetry(any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
