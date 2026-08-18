package com.minikun.planner;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

public record PlannerEvent(
        UUID id,
        String conversationId,
        String title,
        String note,
        Instant startsAt,
        ZoneId timezone,
        int remindBeforeMinutes,
        PlannerRecurrence recurrence,
        String status,
        Instant nextNotifyAt,
        Instant createdAt,
        Instant updatedAt) {
    public PlannerEvent {
        Objects.requireNonNull(id, "planner event id must not be null");
        Objects.requireNonNull(conversationId, "conversation id must not be null");
        Objects.requireNonNull(title, "planner event title must not be null");
        Objects.requireNonNull(note, "planner event note must not be null");
        Objects.requireNonNull(startsAt, "planner event start must not be null");
        Objects.requireNonNull(timezone, "planner event timezone must not be null");
        Objects.requireNonNull(recurrence, "planner recurrence must not be null");
        Objects.requireNonNull(status, "planner event status must not be null");
        Objects.requireNonNull(nextNotifyAt, "planner notification time must not be null");
        Objects.requireNonNull(createdAt, "planner event created time must not be null");
        Objects.requireNonNull(updatedAt, "planner event updated time must not be null");
        if (title.isBlank()) {
            throw new IllegalArgumentException("planner event title must not be blank");
        }
        if (remindBeforeMinutes < 0) {
            throw new IllegalArgumentException("remind-before minutes must not be negative");
        }
    }

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
