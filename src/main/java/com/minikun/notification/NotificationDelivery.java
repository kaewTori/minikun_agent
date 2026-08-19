package com.minikun.notification;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One external delivery attempt. */
public record NotificationDelivery(
        UUID id,
        String sourceType,
        String sourceId,
        NotificationChannel channel,
        String title,
        String message,
        NotificationDeliveryStatus status,
        Instant attemptedAt,
        Instant completedAt,
        String failureReason) {

    public NotificationDelivery {
        Objects.requireNonNull(id, "delivery id must not be null");
        Objects.requireNonNull(sourceType, "delivery source type must not be null");
        Objects.requireNonNull(sourceId, "delivery source id must not be null");
        Objects.requireNonNull(channel, "delivery channel must not be null");
        Objects.requireNonNull(title, "delivery title must not be null");
        Objects.requireNonNull(message, "delivery message must not be null");
        Objects.requireNonNull(status, "delivery status must not be null");
        Objects.requireNonNull(attemptedAt, "delivery attempt time must not be null");
        Objects.requireNonNull(completedAt, "delivery completion time must not be null");
        failureReason = Objects.requireNonNullElse(failureReason, "");
    }
}
