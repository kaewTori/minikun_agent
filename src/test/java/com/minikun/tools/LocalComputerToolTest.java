package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.computer.ComputerAudit;
import com.minikun.computer.ComputerAuditStore;
import com.minikun.computer.ComputerCommandGateway;
import com.minikun.computer.ComputerRoot;
import com.minikun.computer.LocalComputerService;
import com.minikun.guardian.GuardianCommandResult;
import com.minikun.planner.PendingPlannerConfirmation;
import com.minikun.planner.PlannerConfirmationService;
import com.minikun.planner.PlannerConfirmationStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalComputerToolTest {
    @TempDir Path directory;

    @Test
    void mutationRequiresAStoredSecondTurnConfirmation() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC);
        InMemoryConfirmationStore store = new InMemoryConfirmationStore();
        PlannerConfirmationService confirmations = new PlannerConfirmationService(store, clock);
        LocalComputerTool tool = new LocalComputerTool(service(clock), Optional.of(confirmations));
        ConversationId conversation = new ConversationId("computer-confirmation");
        ToolCallContext context = new ToolCallContext(conversation, "preview", "owner");

        ToolResult direct = tool.execute(context, Map.of("action", "write", "root", "docs",
                "path", "unsafe.txt", "content", "no preview", "confirmed", true));
        assertFalse(direct.success());
        assertFalse(Files.exists(directory.resolve("unsafe.txt")));

        ToolResult preview = tool.execute(context, Map.of("action", "write", "root", "docs",
                "path", "safe.txt", "content", "confirmed content"));
        assertTrue(preview.success());
        assertTrue((Boolean) ((Map<?, ?>) preview.value()).get("requires_confirmation"));
        assertFalse(Files.exists(directory.resolve("safe.txt")));

        ComputerConfirmationRouter router = new ComputerConfirmationRouter(
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(tool))), confirmations);
        Optional<ToolEvidence> evidence = router.route("ยืนยันครับ", conversation, "owner");

        assertTrue(evidence.isPresent());
        assertTrue(evidence.get().success());
        assertEquals("confirmed content", Files.readString(directory.resolve("safe.txt")));
        assertTrue(confirmations.find(conversation, "owner").isEmpty());
    }

    private LocalComputerService service(Clock clock) {
        ComputerAuditStore audit = new ComputerAuditStore() {
            @Override public void save(ComputerAudit value) {}
            @Override public List<ComputerAudit> list(String ownerId, int limit) { return List.of(); }
        };
        ComputerCommandGateway commands = new ComputerCommandGateway(
                (command, timeout) -> new GuardianCommandResult(0, false, ""), Duration.ofSeconds(1),
                List.of(), Set.of("Finder"));
        return new LocalComputerService(List.of(new ComputerRoot("docs", directory)), audit, commands,
                clock, 65_536, 65_536, 4, 100);
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
