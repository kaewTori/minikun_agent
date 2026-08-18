package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import com.minikun.planner.PlannerService;
import com.minikun.planner.PlannerStore;

class RelativeReminderToolRouterTest {
    private static final Instant NOW = Instant.parse("2026-08-18T16:55:56Z");

    @Test
    void calculatesTenMinutesOnTheServerAndKeepsConfirmationFlow() {
        InMemoryPlannerStore plannerStore = new InMemoryPlannerStore();
        InMemoryConfirmationStore confirmationStore = new InMemoryConfirmationStore();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PlannerConfirmationService confirmations = new PlannerConfirmationService(confirmationStore, clock);
        PlannerManageTool plannerTool = new PlannerManageTool(
                new PlannerService(plannerStore, clock), confirmations);
        ToolExecutor executor = new DefaultToolExecutor(new DefaultToolRegistry(List.of(plannerTool)));
        RelativeReminderToolRouter router = new RelativeReminderToolRouter(executor, clock, "Asia/Bangkok");
        ConversationId conversation = new ConversationId("relative-reminder");

        ToolEvidence proposal = router.route(
                "มินิคุง อีก 10 นาทีช่วยแจ้งเตือนเราว่ารถมาให้หน่อย", conversation).orElseThrow();

        assertTrue(proposal.success());
        assertTrue(proposal.content().contains("2026-08-19T00:05:56+07:00"));
        assertTrue(plannerStore.events.isEmpty());
        assertEquals("รถมา", confirmationStore.value.arguments().get("title"));
        assertEquals("2026-08-19T00:05:56+07:00", confirmationStore.value.arguments().get("at"));

        ToolEvidence confirmed = new PlannerConfirmationRouter(executor, confirmations)
                .route("ยืนยัน", conversation).orElseThrow();

        assertTrue(confirmed.success());
        assertEquals(1, plannerStore.events.size());
        assertEquals(Instant.parse("2026-08-18T17:05:56Z"), plannerStore.events.getFirst().startsAt());
    }

    @Test
    void supportsEnglishRelativeReminderRequests() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InMemoryConfirmationStore confirmationStore = new InMemoryConfirmationStore();
        PlannerConfirmationService confirmations = new PlannerConfirmationService(confirmationStore, clock);
        PlannerManageTool plannerTool = new PlannerManageTool(
                new PlannerService(new InMemoryPlannerStore(), clock), confirmations);
        RelativeReminderToolRouter router = new RelativeReminderToolRouter(
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(plannerTool))), clock, "Asia/Bangkok");

        ToolEvidence proposal = router.route("remind me in 2 hours about the meeting", new ConversationId("english"))
                .orElseThrow();

        assertTrue(proposal.success());
        assertTrue(proposal.content().contains("2026-08-19T01:55:56+07:00"));
    }

    @Test
    void leavesNonReminderRelativeTimeForTheModel() {
        RelativeReminderToolRouter router = new RelativeReminderToolRouter(
                new DefaultToolExecutor(new DefaultToolRegistry(List.of())),
                Clock.fixed(NOW, ZoneOffset.UTC), "Asia/Bangkok");

        assertTrue(router.route("อีก 10 นาทีจะกี่โมง", new ConversationId("not-reminder")).isEmpty());
        assertTrue(router.route("พรุ่งนี้ช่วยแจ้งเตือนรถมา", new ConversationId("absolute" )).isEmpty());
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
            events.removeIf(existing -> existing.id().equals(event.id()));
            events.add(event);
            return event;
        }

        @Override
        public boolean cancel(UUID id, String conversationId, Instant updatedAt) {
            return events.removeIf(event -> event.id().equals(id)
                    && event.conversationId().equals(conversationId));
        }

        @Override
        public void markDelivered(PlannerEvent event, Instant now) {
        }
    }
}
