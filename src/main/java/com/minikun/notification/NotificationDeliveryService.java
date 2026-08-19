package com.minikun.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Sends notifications and records each attempt without coupling delivery to audit persistence. */
@Component
public final class NotificationDeliveryService implements NotificationDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDeliveryService.class);
    private static final int MAX_FAILURE_REASON = 500;

    private final NotificationTransport transport;
    private final Optional<NotificationDeliveryStore> store;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public NotificationDeliveryService(
            NotificationTransport transport,
            Optional<NotificationDeliveryStore> store,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.transport = Objects.requireNonNull(transport, "notification transport must not be null");
        this.store = Objects.requireNonNull(store, "notification store optional must not be null");
        this.clock = Objects.requireNonNull(clock, "notification clock must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meter registry must not be null");
    }

    @Override
    public void publish(NotificationRequest request) {
        Objects.requireNonNull(request, "notification request must not be null");
        Instant attemptedAt = clock.instant();
        try {
            transport.publish(request.channel(), request.title(), request.message(), request.priority(), request.tags());
            save(request, NotificationDeliveryStatus.DELIVERED, attemptedAt, clock.instant(), "");
            increment(request, NotificationDeliveryStatus.DELIVERED);
        } catch (RuntimeException exception) {
            save(request, NotificationDeliveryStatus.FAILED, attemptedAt, clock.instant(), failureReason(exception));
            increment(request, NotificationDeliveryStatus.FAILED);
            throw exception;
        }
    }

    private void save(
            NotificationRequest request,
            NotificationDeliveryStatus status,
            Instant attemptedAt,
            Instant completedAt,
            String failureReason) {
        if (store.isEmpty()) {
            return;
        }
        try {
            store.get().save(new NotificationDelivery(
                    UUID.randomUUID(), request.sourceType(), request.sourceId(), request.channel(),
                    request.title(), request.message(), status, attemptedAt, completedAt, failureReason));
        } catch (RuntimeException exception) {
            LOGGER.warn("process=notification_history event=record_failed source_type={} source_id={} reason={}",
                    request.sourceType(), request.sourceId(), exception.getMessage());
        }
    }

    private void increment(NotificationRequest request, NotificationDeliveryStatus status) {
        Counter.builder("minikun.notification.deliveries")
                .tag("source", request.sourceType().toLowerCase(java.util.Locale.ROOT))
                .tag("channel", request.channel().name().toLowerCase(java.util.Locale.ROOT))
                .tag("status", status.name().toLowerCase(java.util.Locale.ROOT))
                .register(meterRegistry)
                .increment();
    }

    private String failureReason(RuntimeException exception) {
        String message = Objects.requireNonNullElse(exception.getMessage(), exception.getClass().getSimpleName());
        return message.length() <= MAX_FAILURE_REASON ? message : message.substring(0, MAX_FAILURE_REASON);
    }
}
