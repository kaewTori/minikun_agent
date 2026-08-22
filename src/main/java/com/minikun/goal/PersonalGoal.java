package com.minikun.goal;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

public record PersonalGoal(
        UUID id,
        String ownerId,
        String conversationId,
        String title,
        String description,
        GoalStatus status,
        int progressPercent,
        String metric,
        double currentValue,
        double targetValue,
        Instant nextReviewAt,
        ZoneId timezone,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {
    public PersonalGoal {
        Objects.requireNonNull(id, "goal id must not be null");
        ownerId = required(ownerId, "owner id");
        conversationId = required(conversationId, "conversation id");
        title = required(title, "goal title");
        description = Objects.requireNonNullElse(description, "").trim();
        Objects.requireNonNull(status, "goal status must not be null");
        if (progressPercent < 0 || progressPercent > 100) {
            throw new IllegalArgumentException("goal progress must be between 0 and 100");
        }
        metric = Objects.requireNonNullElse(metric, "").trim();
        if (!Double.isFinite(currentValue) || !Double.isFinite(targetValue)) {
            throw new IllegalArgumentException("goal metric values must be finite");
        }
        Objects.requireNonNull(timezone, "goal timezone must not be null");
        Objects.requireNonNull(createdAt, "goal created time must not be null");
        Objects.requireNonNull(updatedAt, "goal updated time must not be null");
        if (status == GoalStatus.COMPLETED && completedAt == null) {
            throw new IllegalArgumentException("completed goal must have completedAt");
        }
        if (status != GoalStatus.COMPLETED && completedAt != null) {
            throw new IllegalArgumentException("open goal must not have completedAt");
        }
    }

    public boolean open() { return status.open(); }

    private static String required(String value, String field) {
        if (value == null || value.isBlank() || "*".equals(value)) {
            throw new IllegalArgumentException(field + " must not be blank or wildcard");
        }
        return value.trim();
    }
}
