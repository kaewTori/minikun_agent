package com.minikun.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.minikun.sync.SyncEventBroker;

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
                (channel, title, message, priority, tags) -> true, Optional.of(store),
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
                (channel, title, message, priority, tags) -> true, Optional.of(brokenStore),
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());

        service.publish(REQUEST);
    }

    @Test
    void prefersBrowserDeliveryWhenAClientIsConnected() {
        RecordingStore store = new RecordingStore();
        SyncEventBroker browserEvents = mock(SyncEventBroker.class);
        when(browserEvents.publishNotification(eq("default"), anyMap())).thenReturn(true);
        NotificationTransport ntfy = (channel, title, message, priority, tags) -> {
            throw new AssertionError("ntfy should not be used when browser delivery succeeds");
        };
        NotificationDeliveryService service = new NotificationDeliveryService(
                ntfy, Optional.of(store), Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry(), Optional.of(browserEvents));

        service.publish(REQUEST);

        verify(browserEvents).publishNotification(eq("default"), anyMap());
        assertEquals(NotificationDeliveryStatus.DELIVERED, store.deliveries.getFirst().status());
    }

    @Test
    void keepsTheReplyLinkWhenDeliveringToTheBrowser() {
        SyncEventBroker events = mock(SyncEventBroker.class);
        when(events.publishNotification(eq("default"), anyMap())).thenReturn(true);
        var service = new NotificationDeliveryService((channel, title, message, priority, tags) -> true,
                Optional.empty(), Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry(), Optional.of(events));
        String url = "https://mini-kun:8443/cockpit/?view=chat&reply_symbol=SCHD";
        service.publish(new NotificationRequest("INVESTMENT", "default:today", NotificationChannel.REMINDER,
                "Investment", "สรุปข่าว", 3, "investment", url));
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> payload = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(events).publishNotification(eq("default"), payload.capture());
        assertEquals(url, payload.getValue().get("clickUrl"));
    }

    @Test
    void fallsBackToNtfyWhenNoBrowserClientIsConnected() {
        SyncEventBroker browserEvents = mock(SyncEventBroker.class);
        when(browserEvents.publishNotification(eq("default"), anyMap())).thenReturn(false);
        NotificationTransport ntfy = mock(NotificationTransport.class);
        when(ntfy.publish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        NotificationDeliveryService service = new NotificationDeliveryService(
                ntfy, Optional.empty(), Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry(), Optional.of(browserEvents));

        service.publish(REQUEST);

        verify(ntfy).publish(NotificationChannel.REMINDER, REQUEST.title(), REQUEST.message(),
                REQUEST.priority(), REQUEST.tags());
        verify(browserEvents).publishNotification(eq("default"), anyMap());
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
