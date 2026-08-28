package com.minikun.relationship;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A durable open loop in the relationship, distinct from factual memory and tasks. */
public record ConversationThread(
        UUID id,
        String ownerId,
        String sourceConversationId,
        String topic,
        String summary,
        String lastDecision,
        String unresolvedQuestion,
        ConversationThreadStatus status,
        Instant checkInAt,
        boolean checkInConsent,
        Instant lastCheckInAt,
        Instant createdAt,
        Instant updatedAt) {

    public ConversationThread {
        id = Objects.requireNonNull(id, "id");
        ownerId = required(ownerId, "ownerId", 255);
        sourceConversationId = required(sourceConversationId, "sourceConversationId", 255);
        topic = required(topic, "topic", 240);
        summary = bounded(summary, 2000);
        lastDecision = bounded(lastDecision, 1000);
        unresolvedQuestion = bounded(unresolvedQuestion, 1000);
        status = Objects.requireNonNull(status, "status");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (checkInAt != null && !checkInConsent) {
            throw new IllegalArgumentException("a scheduled check-in requires explicit consent");
        }
    }

    public boolean dueAt(Instant now) {
        return status == ConversationThreadStatus.OPEN && checkInConsent && checkInAt != null
                && !checkInAt.isAfter(now) && (lastCheckInAt == null || lastCheckInAt.isBefore(checkInAt));
    }

    private static String required(String value, String field, int maximum) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return bounded(normalized, maximum);
    }

    private static String bounded(String value, int maximum) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum).stripTrailing();
    }
}
