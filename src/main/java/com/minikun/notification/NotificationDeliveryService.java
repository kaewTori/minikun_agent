package com.minikun.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.minikun.proactive.ProactiveAttentionBudget;
import com.minikun.sync.SyncEventBroker;

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
    private final ProactiveAttentionBudget attentionBudget;
    private final Optional<SyncEventBroker> browserEvents;
    private final boolean browserEnabled;
    private final boolean ntfyFallbackEnabled;
    private final String browserOwnerId;

    @Autowired
    public NotificationDeliveryService(
            NotificationTransport transport,
            Optional<NotificationDeliveryStore> store,
            Clock clock,
            MeterRegistry meterRegistry,
            ProactiveAttentionBudget attentionBudget,
            Optional<SyncEventBroker> browserEvents,
            @Value("${minikun.notification.browser.enabled:true}") boolean browserEnabled,
            @Value("${minikun.notification.ntfy-fallback.enabled:true}") boolean ntfyFallbackEnabled,
            @Value("${minikun.sync.owner-id:default}") String browserOwnerId) {
        this.transport = Objects.requireNonNull(transport, "notification transport must not be null");
        this.store = Objects.requireNonNull(store, "notification store optional must not be null");
        this.clock = Objects.requireNonNull(clock, "notification clock must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meter registry must not be null");
        this.attentionBudget = attentionBudget;
        this.browserEvents = Objects.requireNonNull(browserEvents, "browser event broker optional must not be null");
        this.browserEnabled = browserEnabled;
        this.ntfyFallbackEnabled = ntfyFallbackEnabled;
        String owner = Objects.requireNonNullElse(browserOwnerId, "").trim();
        this.browserOwnerId = owner.isBlank() ? "default" : owner;
    }

    public NotificationDeliveryService(
            NotificationTransport transport,
            Optional<NotificationDeliveryStore> store,
            Clock clock,
            MeterRegistry meterRegistry) {
        this(transport, store, clock, meterRegistry, null, Optional.empty(), true, true, "default");
    }

    public NotificationDeliveryService(
            NotificationTransport transport,
            Optional<NotificationDeliveryStore> store,
            Clock clock,
            MeterRegistry meterRegistry,
            Optional<SyncEventBroker> browserEvents) {
        this(transport, store, clock, meterRegistry, null, browserEvents, true, true, "default");
    }

    @Override
    public void publish(NotificationRequest request) {
        Objects.requireNonNull(request, "notification request must not be null");
        if (attentionBudget != null && !attentionBudget.tryAcquire(request)) {
            LOGGER.info("process=notification event=skipped source_type={} reason=attention_budget",
                    request.sourceType());
            Counter.builder("minikun.notification.skipped")
                    .tag("source", request.sourceType().toLowerCase(java.util.Locale.ROOT))
                    .tag("reason", "attention_budget").register(meterRegistry).increment();
            return;
        }
        Instant attemptedAt = clock.instant();
        try {
            if (!publishToBrowser(request)) {
                if (!ntfyFallbackEnabled) {
                    throw new IllegalStateException("browser notification has no active client");
                }
                boolean delivered = request.clickUrl().isBlank()
                        ? transport.publish(request.channel(), request.title(), request.message(), request.priority(), request.tags())
                        : transport.publish(request.channel(), request.title(), request.message(), request.priority(), request.tags(), request.clickUrl());
                if (!delivered) {
                    throw new IllegalStateException("notification transport did not deliver");
                }
                incrementTransport(request, "ntfy");
            }
            save(request, NotificationDeliveryStatus.DELIVERED, attemptedAt, clock.instant(), "");
            increment(request, NotificationDeliveryStatus.DELIVERED);
        } catch (RuntimeException exception) {
            save(request, NotificationDeliveryStatus.FAILED, attemptedAt, clock.instant(), failureReason(exception));
            increment(request, NotificationDeliveryStatus.FAILED);
            throw exception;
        }
    }

    private boolean publishToBrowser(NotificationRequest request) {
        if (!browserEnabled || browserEvents.isEmpty()) return false;
        try {
            boolean delivered = browserEvents.get().publishNotification(browserOwnerId, Map.of(
                    "sourceType", request.sourceType(),
                    "sourceId", request.sourceId(),
                    "title", request.title(),
                    "message", request.message(),
                    "priority", request.priority(),
                    "tags", request.tags(),
                    "clickUrl", request.clickUrl()));
            if (delivered) incrementTransport(request, "browser");
            return delivered;
        } catch (RuntimeException exception) {
            LOGGER.warn("process=notification event=browser_publish_failed source_type={} source_id={} reason={}",
                    request.sourceType(), request.sourceId(), exception.getMessage());
            return false;
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

    private void incrementTransport(NotificationRequest request, String transport) {
        Counter.builder("minikun.notification.transport")
                .tag("source", request.sourceType().toLowerCase(java.util.Locale.ROOT))
                .tag("channel", request.channel().name().toLowerCase(java.util.Locale.ROOT))
                .tag("transport", transport)
                .register(meterRegistry)
                .increment();
    }

    private String failureReason(RuntimeException exception) {
        String message = Objects.requireNonNullElse(exception.getMessage(), exception.getClass().getSimpleName());
        return message.length() <= MAX_FAILURE_REASON ? message : message.substring(0, MAX_FAILURE_REASON);
    }
}
