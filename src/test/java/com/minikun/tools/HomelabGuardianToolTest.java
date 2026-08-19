package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.guardian.GuardianActionAudit;
import com.minikun.guardian.GuardianActionDefinition;
import com.minikun.guardian.GuardianActionService;
import com.minikun.guardian.GuardianAuditStore;
import com.minikun.guardian.GuardianBackupChecker;
import com.minikun.guardian.GuardianCommandResult;
import com.minikun.guardian.GuardianLogReader;
import com.minikun.guardian.HomelabGuardianService;
import com.minikun.planner.PendingPlannerConfirmation;
import com.minikun.planner.PlannerConfirmationService;
import com.minikun.planner.PlannerConfirmationStore;
import com.minikun.systemhealth.SystemHealthReport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HomelabGuardianToolTest {
    @Test
    void executesAllowlistedActionOnlyAfterSecondTurnConfirmation() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC);
        InMemoryConfirmationStore confirmationStore = new InMemoryConfirmationStore();
        PlannerConfirmationService confirmations = new PlannerConfirmationService(confirmationStore, clock);
        AtomicInteger executions = new AtomicInteger();
        List<GuardianActionAudit> audits = new ArrayList<>();
        GuardianActionService actions = new GuardianActionService(
                List.of(new GuardianActionDefinition("restart-ollama", "Restart Ollama",
                        List.of("/fixed/manager", "restart", "ollama"))),
                (command, timeout) -> {
                    executions.incrementAndGet();
                    return new GuardianCommandResult(0, false, "ok");
                }, auditStore(audits), clock, Duration.ofSeconds(5));
        HomelabGuardianTool tool = new HomelabGuardianTool(guardian(clock), actions, Optional.of(confirmations));
        ConversationId conversation = new ConversationId("guardian-conversation");
        ToolCallContext context = new ToolCallContext(conversation, "proposal", "owner");

        ToolResult proposal = tool.execute(context,
                Map.of("action", "execute", "action_id", "restart-ollama"));

        assertTrue(proposal.success());
        assertTrue(((Map<?, ?>) proposal.value()).get("requires_confirmation").equals(true));
        assertEquals(0, executions.get());

        ToolExecutor executor = new DefaultToolExecutor(new DefaultToolRegistry(List.of(tool)));
        GuardianConfirmationRouter router = new GuardianConfirmationRouter(executor, confirmations);
        Optional<ToolEvidence> confirmed = router.route("ยืนยันครับ", conversation, "owner");

        assertTrue(confirmed.isPresent());
        assertTrue(confirmed.get().success());
        assertEquals(1, executions.get());
        assertEquals(List.of("REQUESTED", "SUCCEEDED"), audits.stream().map(GuardianActionAudit::status).toList());
        assertTrue(confirmations.find(conversation, "owner").isEmpty());
    }

    @Test
    void doesNotExposeCommandsAndRejectsUnknownLogSources() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC);
        GuardianActionService actions = new GuardianActionService(
                List.of(new GuardianActionDefinition("restart-ollama", "Restart Ollama",
                        List.of("/secret/executable", "secret-argument"))),
                (command, timeout) -> new GuardianCommandResult(0, false, "ok"),
                auditStore(new ArrayList<>()), clock, Duration.ofSeconds(5));
        HomelabGuardianTool tool = new HomelabGuardianTool(guardian(clock), actions, Optional.empty());

        ToolResult listed = tool.execute(new ToolCallContext(new ConversationId("c"), "list"),
                Map.of("action", "actions"));
        ToolResult invalidLog = tool.execute(new ToolCallContext(new ConversationId("c"), "logs"),
                Map.of("action", "logs", "source", "../../private"));

        assertTrue(listed.success());
        assertFalse(listed.value().toString().contains("secret/executable"));
        assertFalse(listed.value().toString().contains("secret-argument"));
        assertFalse(invalidLog.success());
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, invalidLog.errorCode());
    }

    private HomelabGuardianService guardian(Clock clock) {
        SystemHealthReport report = new SystemHealthReport("UP", true,
                Map.of("status", "UP"), Map.of("status", "UP"), Map.of("status", "UP"),
                Map.of("status", "UP"), Map.of("status", "UP"), Map.of("status", "RUNNING"), Map.of());
        return new HomelabGuardianService(() -> report, new GuardianLogReader(List.of()),
                new GuardianBackupChecker(List.of(), Duration.ofHours(36), clock), clock);
    }

    private GuardianAuditStore auditStore(List<GuardianActionAudit> audits) {
        return new GuardianAuditStore() {
            @Override public void save(GuardianActionAudit audit) { audits.add(audit); }
            @Override public List<GuardianActionAudit> list(String ownerId, int limit) {
                return audits.stream().filter(value -> ownerId.equals(value.ownerId())).limit(limit).toList();
            }
        };
    }

    private static final class InMemoryConfirmationStore implements PlannerConfirmationStore {
        private PendingPlannerConfirmation value;
        @Override public void save(PendingPlannerConfirmation confirmation) { value = confirmation; }
        @Override public Optional<PendingPlannerConfirmation> find(String conversationId, Instant now) {
            return value != null && value.conversationId().equals(conversationId) && value.expiresAt().isAfter(now)
                    ? Optional.of(value) : Optional.empty();
        }
        @Override public void clear(String conversationId) {
            if (value != null && value.conversationId().equals(conversationId)) value = null;
        }
    }
}
