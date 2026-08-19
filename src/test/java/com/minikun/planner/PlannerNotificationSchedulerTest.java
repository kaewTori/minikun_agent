package com.minikun.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.notification.NotificationRequest;
import com.minikun.notification.NotificationSchedulerMonitor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PlannerNotificationSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-08-19T17:30:00Z"); // 00:30 Asia/Bangkok

    @Test
    void deliversExplicitReminderDuringProactiveQuietHours() {
        InMemoryStore store = new InMemoryStore();
        PlannerService planner = new PlannerService(store, Clock.fixed(NOW.minusSeconds(60), ZoneOffset.UTC));
        PlannerEvent event = planner.create(new ConversationId("conversation"), "แจ้งเตือนตอนกลางคืน", "",
                NOW.toString(), "Asia/Bangkok", 1, "NONE");
        List<NotificationRequest> published = new ArrayList<>();
        NotificationSchedulerMonitor monitor = new NotificationSchedulerMonitor(
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry(), Duration.ofMinutes(2), 3);
        PlannerNotificationScheduler scheduler = new PlannerNotificationScheduler(
                planner, published::add, monitor, Clock.fixed(NOW, ZoneOffset.UTC));

        scheduler.deliverDueReminders();

        assertEquals(1, published.size());
        assertEquals(event.id().toString(), published.getFirst().sourceId());
        assertTrue(store.events.getFirst().status().equals("DONE"));
    }

    private static final class InMemoryStore implements PlannerStore {
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
            return List.copyOf(events);
        }

        @Override
        public List<PlannerEvent> findDue(Instant now) {
            return events.stream().filter(event -> event.active() && !event.nextNotifyAt().isAfter(now)).toList();
        }

        @Override
        public PlannerEvent update(PlannerEvent event) {
            return event;
        }

        @Override
        public boolean cancel(UUID id, String conversationId, Instant updatedAt) {
            return false;
        }

        @Override
        public void markDelivered(PlannerEvent event, Instant now) {
            int index = events.indexOf(event);
            events.set(index, new PlannerEvent(event.id(), event.conversationId(), event.title(), event.note(),
                    event.startsAt(), event.timezone(), event.remindBeforeMinutes(), event.recurrence(), "DONE",
                    event.nextNotifyAt(), event.createdAt(), now));
        }
    }
}
