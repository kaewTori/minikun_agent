package com.minikun.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.minikun.tools.PlannerManageTool;
import com.minikun.tools.ToolCallContext;
import com.minikun.tools.ToolResult;

class PlannerServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void plannerRequiresConfirmationBeforeWritingAndStoresBangkokTime() {
        InMemoryPlannerStore store = new InMemoryPlannerStore();
        PlannerService planner = new PlannerService(store, Clock.fixed(NOW, ZoneId.of("UTC")));
        PlannerManageTool tool = new PlannerManageTool(planner,
                new PlannerConfirmationService(new InMemoryPlannerConfirmationStore(), Clock.fixed(NOW, ZoneId.of("UTC"))));
        ToolCallContext context = new ToolCallContext(new ConversationId("conversation"), "call-1");

        ToolResult proposal = tool.execute(context, java.util.Map.of(
                "action", "create",
                "title", "ประชุมทีม",
                "at", "2026-08-18T09:00:00",
                "timezone", "Asia/Bangkok"));

        assertTrue(proposal.success());
        assertTrue(((java.util.Map<?, ?>) proposal.value()).get("requires_confirmation") instanceof Boolean);
        assertTrue(store.events.isEmpty());

        ToolResult saved = tool.execute(context, java.util.Map.of(
                "action", "create",
                "title", "ประชุมทีม",
                "at", "2026-08-18T09:00:00",
                "timezone", "Asia/Bangkok",
                "confirmed", true,
                "remind_before_minutes", 30));

        assertTrue(saved.success());
        assertEquals(1, store.events.size());
        assertEquals(Instant.parse("2026-08-18T02:00:00Z"), store.events.getFirst().startsAt());
        assertEquals(Instant.parse("2026-08-18T01:30:00Z"), store.events.getFirst().nextNotifyAt());
    }

    @Test
    void acknowledgesAndCreatesOneShotSnoozedReminder() {
        InMemoryPlannerStore store = new InMemoryPlannerStore();
        PlannerService planner = new PlannerService(store, Clock.fixed(NOW, ZoneId.of("UTC")));
        ConversationId conversation = new ConversationId("conversation");
        PlannerEvent original = planner.create(conversation, "ประชุมทีม", "bring notes",
                "2026-08-18T09:00:00", "Asia/Bangkok", 15, "NONE");

        assertTrue(planner.acknowledge(conversation, original.id()));
        PlannerEvent snoozed = planner.snooze(conversation, original.id(),
                "2026-08-18T10:00:00", "Asia/Bangkok");

        assertEquals(2, store.events.size());
        assertEquals(Instant.parse("2026-08-18T03:00:00Z"), snoozed.nextNotifyAt());
        assertEquals(PlannerRecurrence.NONE, snoozed.recurrence());
        assertEquals(List.of("ACKNOWLEDGED", "SNOOZED"), store.actions);
    }

    private static final class InMemoryPlannerStore implements PlannerStore {
        private final List<PlannerEvent> events = new ArrayList<>();
        private final List<String> actions = new ArrayList<>();

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
            // Not needed by this focused persistence contract test.
        }

        @Override
        public void recordAction(UUID actionId, String conversationId, UUID eventId,
                String action, Instant snoozedUntil, Instant createdAt) {
            actions.add(action);
        }
    }

    private static final class InMemoryPlannerConfirmationStore implements PlannerConfirmationStore {
        private PendingPlannerConfirmation confirmation;

        @Override
        public void save(PendingPlannerConfirmation value) {
            confirmation = value;
        }

        @Override
        public Optional<PendingPlannerConfirmation> find(String conversationId, Instant now) {
            return confirmation != null && confirmation.conversationId().equals(conversationId)
                    && confirmation.expiresAt().isAfter(now) ? Optional.of(confirmation) : Optional.empty();
        }

        @Override
        public void clear(String conversationId) {
            if (confirmation != null && confirmation.conversationId().equals(conversationId)) {
                confirmation = null;
            }
        }
    }
}
