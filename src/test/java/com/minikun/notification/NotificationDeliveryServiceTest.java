package com.minikun.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class NotificationDeliveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-20T02:00:00Z");
    private static final NotificationRequest REQUEST = new NotificationRequest(
            "PLANNER", "reminder-1", NotificationChannel.REMINDER,
            "Mini-kun reminder", "ถึงเวลาแล้ว", 3, "bell");

    @Test
    void recordsSuccessfulDeliveryAndMetric() {
        RecordingStore store = new RecordingStore();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationDeliveryService service = new NotificationDeliveryService(
                (channel, title, message, priority, tags) -> { }, Optional.of(store),
                Clock.fixed(NOW, ZoneOffset.UTC), registry);

        service.publish(REQUEST);

        assertEquals(1, store.deliveries.size());
        assertEquals(NotificationDeliveryStatus.DELIVERED, store.deliveries.getFirst().status());
        assertEquals("reminder-1", store.deliveries.getFirst().sourceId());
        assertEquals(1.0, registry.get("minikun.notification.deliveries")
                .tag("source", "planner").tag("status", "delivered").counter().count());
    }

    @Test
    void recordsFailureAndLetsSchedulerRetry() {
        RecordingStore store = new RecordingStore();
        NotificationDeliveryService service = new NotificationDeliveryService(
                (channel, title, message, priority, tags) -> {
                    throw new IllegalStateException("ntfy unavailable");
                }, Optional.of(store), Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());

        assertThrows(IllegalStateException.class, () -> service.publish(REQUEST));

        assertEquals(1, store.deliveries.size());
        assertEquals(NotificationDeliveryStatus.FAILED, store.deliveries.getFirst().status());
        assertEquals("ntfy unavailable", store.deliveries.getFirst().failureReason());
    }

    @Test
    void auditStoreFailureDoesNotTurnSuccessfulNotificationIntoRetry() {
        NotificationDeliveryStore brokenStore = new NotificationDeliveryStore() {
            @Override
            public void save(NotificationDelivery delivery) {
                throw new IllegalStateException("database unavailable");
            }

            @Override
            public List<NotificationDelivery> list(
                    String sourceType, String sourceId, NotificationDeliveryStatus status, int limit) {
                return List.of();
            }
        };
        NotificationDeliveryService service = new NotificationDeliveryService(
                (channel, title, message, priority, tags) -> { }, Optional.of(brokenStore),
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());

        service.publish(REQUEST);
    }

    private static final class RecordingStore implements NotificationDeliveryStore {
        private final List<NotificationDelivery> deliveries = new ArrayList<>();

        @Override
        public void save(NotificationDelivery delivery) {
            deliveries.add(delivery);
        }

        @Override
        public List<NotificationDelivery> list(
                String sourceType, String sourceId, NotificationDeliveryStatus status, int limit) {
            return List.copyOf(deliveries);
        }
    }
}
