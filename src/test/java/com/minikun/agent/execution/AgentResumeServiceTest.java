package com.minikun.agent.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.tools.ToolErrorCode;
import com.minikun.tools.ToolExecutor;
import com.minikun.tools.ToolResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentResumeServiceTest {
    private AgentExecutionService executions;
    private AgentRun run;

    @BeforeEach
    void setUp() {
        executions = new AgentExecutionService(new InMemoryAgentExecutionStore(), new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC), 1, 12);
        run = executions.start("owner", "conversation", new AgentPlanDraft(
                "complete a persisted action", List.of("execute", "verify"))).orElseThrow();
        executions.beginStep(run.id(), "call-1", "recoverable.tool", Map.of("name", "value"));
        executions.finishStep(run.id(), "call-1",
                ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "old failure"), false);
        executions.complete(run.id(), "failed before restart");
    }

    @Test
    void replaysPersistedFailedStepAndCompletesRun() {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutor executor = (context, call) -> {
            calls.incrementAndGet();
            assertEquals("owner", context.ownerId());
            assertEquals("value", call.arguments().get("name"));
            return ToolResult.success(Map.of("restored", true));
        };
        AgentResumeService resume = new AgentResumeService(executions, executor, new ObjectMapper());

        AgentExecutionService.AgentRunDetails details = resume.resume("owner", run.id());

        assertEquals(1, calls.get());
        assertEquals(AgentRunStatus.UNVERIFIED, details.run().status());
        assertEquals(AgentStepStatus.COMPLETED, details.steps().get(0).status());
        assertEquals(2, details.steps().get(0).attempts());
    }

    @Test
    void doesNotAllowAnotherOwnerToResumeRun() {
        AgentResumeService resume = new AgentResumeService(executions,
                (context, call) -> ToolResult.success(Map.of()), new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> resume.resume("someone-else", run.id()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void stopsBeforeDependentStepWhenConfirmationOrFailureOccurs(boolean confirmation) {
        executions.beginStep(run.id(), "call-2", "dependent.tool", Map.of());
        executions.finishStep(run.id(), "call-2", ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "old"), false);
        executions.complete(run.id(), "interrupted plan");
        AtomicInteger calls = new AtomicInteger();
        var resume = new AgentResumeService(executions, (context, call) -> {
            calls.incrementAndGet();
            return confirmation ? ToolResult.success(Map.of("requires_confirmation", true))
                    : ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "uncertain outcome");
        }, new ObjectMapper());

        var result = resume.resume("owner", run.id());
        assertEquals(1, calls.get());
        assertEquals(confirmation ? AgentRunStatus.WAITING_CONFIRMATION : AgentRunStatus.FAILED, result.run().status());
        assertEquals(1, result.steps().get(1).attempts());
    }
}
