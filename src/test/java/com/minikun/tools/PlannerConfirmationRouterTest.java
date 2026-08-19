package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.planner.PendingPlannerConfirmation;
import com.minikun.planner.PlannerConfirmationService;
import com.minikun.planner.PlannerConfirmationStore;
import com.minikun.planner.PlannerEvent;
import com.minikun.planner.PlannerRecurrence;
import com.minikun.planner.PlannerService;
import com.minikun.planner.PlannerStore;

class PlannerConfirmationRouterTest {
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void confirmationExecutesTheExactPendingProposalWithoutModelMemory() {
        InMemoryPlannerStore plannerStore = new InMemoryPlannerStore();
        InMemoryConfirmationStore confirmationStore = new InMemoryConfirmationStore();
        Clock clock = Clock.fixed(NOW, ZoneId.of("UTC"));
        PlannerConfirmationService confirmations = new PlannerConfirmationService(confirmationStore, clock);
        PlannerManageTool plannerTool = new PlannerManageTool(
                new PlannerService(plannerStore, clock), confirmations);
        ToolExecutor executor = new DefaultToolExecutor(new DefaultToolRegistry(List.of(plannerTool)));
        PlannerConfirmationRouter router = new PlannerConfirmationRouter(executor, confirmations);
        ConversationId conversation = new ConversationId("conversation-confirmation");

        ToolResult proposal = plannerTool.execute(
                new ToolCallContext(conversation, "proposal-call"),
                Map.of("action", "create", "title", "รถมา", "at", "2026-08-18T09:10:00",
                        "timezone", "Asia/Bangkok"));

        assertTrue(proposal.success());
        assertTrue(plannerStore.events.isEmpty());
        assertTrue(confirmations.find(conversation).isPresent());

        Optional<ToolEvidence> evidence = router.route("ยืนยันครับ", conversation);

        assertTrue(evidence.isPresent());
        assertTrue(evidence.get().success());
        assertTrue(evidence.get().content().contains("บันทึกการแจ้งเตือนเรียบร้อยแล้ว"));
        assertEquals(1, plannerStore.events.size());
        assertEquals("รถมา", plannerStore.events.getFirst().title());
        assertTrue(confirmations.find(conversation).isEmpty());
        assertTrue(router.route("ยืนยัน", conversation).isEmpty());
    }

    @Test
    void doesNotTreatArbitraryConfirmationQuestionAsApproval() {
        InMemoryConfirmationStore confirmationStore = new InMemoryConfirmationStore();
        PlannerConfirmationRouter router = new PlannerConfirmationRouter(
                new DefaultToolExecutor(new DefaultToolRegistry(List.of())),
                new PlannerConfirmationService(confirmationStore, Clock.fixed(NOW, ZoneId.of("UTC"))));

        assertTrue(router.route("ยืนยันเรื่องอะไรเหรอครับ", new ConversationId("empty")).isEmpty());
    }

    @Test
    void doesNotMisrouteGuardianConfirmationToPlannerTool() {
        InMemoryConfirmationStore confirmationStore = new InMemoryConfirmationStore();
        PlannerConfirmationService confirmations = new PlannerConfirmationService(
                confirmationStore, Clock.fixed(NOW, ZoneId.of("UTC")));
        PlannerConfirmationRouter router = new PlannerConfirmationRouter(
                new DefaultToolExecutor(new DefaultToolRegistry(List.of())), confirmations);
        ConversationId conversation = new ConversationId("guardian-pending");
        confirmations.save(conversation, "default", "guardian.execute",
                Map.of("action", "execute", "action_id", "restart-ollama"));

        assertTrue(router.route("ยืนยัน", conversation).isEmpty());
        assertTrue(confirmations.find(conversation).isPresent());
    }

    private static final class InMemoryConfirmationStore implements PlannerConfirmationStore {
        private PendingPlannerConfirmation value;

        @Override
        public void save(PendingPlannerConfirmation confirmation) {
            value = confirmation;
        }

        @Override
        public Optional<PendingPlannerConfirmation> find(String conversationId, Instant now) {
            return value != null && value.conversationId().equals(conversationId)
                    && value.expiresAt().isAfter(now) ? Optional.of(value) : Optional.empty();
        }

        @Override
        public void clear(String conversationId) {
            if (value != null && value.conversationId().equals(conversationId)) {
                value = null;
            }
        }
    }

    private static final class InMemoryPlannerStore implements PlannerStore {
        private final List<PlannerEvent> events = new ArrayList<>();

        @Override
        public PlannerEvent create(PlannerEvent event) {
            events.add(event);
            return event;
        }

        @Override
        public Optional<PlannerEvent> find(UUID id, String conversationId) {
            return events.stream().filter(event -> event.id().equals(id)
                    && event.conversationId().equals(conversationId)).findFirst();
        }

        @Override
        public List<PlannerEvent> list(String conversationId) {
            return events.stream().filter(event -> event.conversationId().equals(conversationId)).toList();
        }

        @Override
        public List<PlannerEvent> findDue(Instant now) {
            return events.stream().filter(event -> event.active() && !event.nextNotifyAt().isAfter(now)).toList();
        }

        @Override
        public PlannerEvent update(PlannerEvent event) {
            cancel(event.id(), event.conversationId(), event.updatedAt());
            events.add(event);
            return event;
        }

        @Override
        public boolean cancel(UUID id, String conversationId, Instant updatedAt) {
            return events.removeIf(event -> event.id().equals(id) && event.conversationId().equals(conversationId));
        }

        @Override
        public void markDelivered(PlannerEvent event, Instant now) {
            // Not needed by this focused confirmation test.
        }
    }
}
