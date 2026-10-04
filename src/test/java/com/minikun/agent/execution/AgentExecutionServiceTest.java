package com.minikun.agent.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.tools.ToolErrorCode;
import com.minikun.tools.ToolResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentExecutionServiceTest {
    private InMemoryAgentExecutionStore store;
    private AgentExecutionService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryAgentExecutionStore();
        service = new AgentExecutionService(store, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC), 1, 12);
    }

    @Test
    void retriesTransientFailureOnceAndCompletesRun() {
        AgentRun run = start("owner-a");
        AgentExecutionStep first = service.beginStep(run.id(), "call-1", "example.tool", Map.of("value", 1));
        ToolResult failure = ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "temporary failure");

        assertEquals(1, first.attempts());
        assertTrue(service.shouldRetry(failure, first.attempts()));
        service.finishStep(run.id(), "call-1", failure, true);
        AgentExecutionStep second = service.beginStep(run.id(), "call-1", "example.tool", Map.of("value", 1));
        assertEquals(2, second.attempts());
        assertTrue(!service.shouldRetry(failure, second.attempts()));
        service.finishStep(run.id(), "call-1", ToolResult.success(Map.of("ok", true)), false);
        service.complete(run.id(), "done");

        AgentExecutionService.AgentRunDetails details = service.details("owner-a", run.id());
        assertEquals(AgentRunStatus.UNVERIFIED, details.run().status());
        assertEquals(AgentStepStatus.COMPLETED, details.steps().get(0).status());
        assertEquals(2, details.steps().get(0).attempts());
    }

    @Test
    void waitsForConfirmationAndDoesNotMarkRunComplete() {
        AgentRun run = start("owner-a");
        service.beginStep(run.id(), "call-1", "write.tool", Map.of());
        service.finishStep(run.id(), "call-1",
                ToolResult.success(Map.of("requires_confirmation", true)), false);

        AgentExecutionService.AgentRunDetails details = service.details("owner-a", run.id());
        assertEquals(AgentRunStatus.WAITING_CONFIRMATION, details.run().status());
        assertEquals(AgentStepStatus.WAITING_CONFIRMATION, details.steps().get(0).status());
        service.complete(run.id(), "model incorrectly said done");
        assertEquals(AgentRunStatus.WAITING_CONFIRMATION, service.find("owner-a", run.id()).status());
    }

    @Test
    void modelStoppingWithoutToolsDoesNotProvePlanCompletion() {
        AgentRun run = start("owner-a");
        service.complete(run.id(), "everything is done");
        assertEquals(AgentRunStatus.UNVERIFIED, service.find("owner-a", run.id()).status());
        assertTrue(service.completionNotice(run.id()).isPresent());
    }

    @Test
    void recordsPermanentToolFailureAndEnforcesOwnerIsolation() {
        AgentRun run = start("owner-a");
        service.beginStep(run.id(), "call-1", "example.tool", Map.of());
        service.finishStep(run.id(), "call-1",
                ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "bad input"), false);
        service.complete(run.id(), "could not finish one action");

        assertEquals(AgentRunStatus.COMPLETED_WITH_ERRORS, service.find("owner-a", run.id()).status());
        assertThrows(IllegalArgumentException.class, () -> service.find("owner-b", run.id()));
        assertEquals(List.of(), service.list("owner-b", null, 20));
    }

    @Test
    void keepsResponseIdThroughRunUpdates() {
        String browserId = "web-5bd3be5d-9548-460c-a4fd-2ab4d9d67ad3";
        AgentRun run = service.start("owner-a",
                com.minikun.agent.minikun_agent.conversation.ConversationId.fromTransport(browserId).value(),
                "chatcmpl-123", new AgentPlanDraft(
                "inspect and verify", List.of("inspect", "verify"))).orElseThrow();
        service.beginStep(run.id(), "call-1", "example.tool", Map.of());
        service.finishStep(run.id(), "call-1", ToolResult.success(Map.of("ok", true)), false);
        service.complete(run.id(), "done");

        assertEquals("chatcmpl-123", service.details("owner-a", run.id()).run().responseId());
        assertEquals(List.of(run.id()), service.list("owner-a", browserId, null, 10).stream()
                .map(AgentRun::id).toList());
        assertTrue(service.list("owner-a", "different-conversation", null, 10).isEmpty());
    }

    private AgentRun start(String owner) {
        return service.start(owner, "conversation", new AgentPlanDraft(
                "do multiple operations", List.of("inspect", "execute", "verify"))).orElseThrow();
    }
}
