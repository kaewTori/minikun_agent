package com.minikun.task;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/** An owner-scoped goal/task with explicit progress and follow-up state. */
public record PersonalTask(
        UUID id,
        String ownerId,
        String conversationId,
        TaskKind kind,
        String title,
        String description,
        TaskStatus status,
        UUID parentId,
        UUID goalId,
        Instant dueAt,
        ZoneId timezone,
        String nextAction,
        String waitingFor,
        Instant followUpAt,
        Instant lastFollowUpAt,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public PersonalTask {
        Objects.requireNonNull(id, "task id must not be null");
        ownerId = requireOwner(ownerId);
        conversationId = requireText(conversationId, "conversation id");
        Objects.requireNonNull(kind, "task kind must not be null");
        title = requireText(title, "task title");
        description = Objects.requireNonNullElse(description, "").trim();
        Objects.requireNonNull(status, "task status must not be null");
        Objects.requireNonNull(timezone, "task timezone must not be null");
        nextAction = Objects.requireNonNullElse(nextAction, "").trim();
        waitingFor = Objects.requireNonNullElse(waitingFor, "").trim();
        Objects.requireNonNull(createdAt, "task created time must not be null");
        Objects.requireNonNull(updatedAt, "task updated time must not be null");
        if (status == TaskStatus.DONE && completedAt == null) {
            throw new IllegalArgumentException("done task must have completedAt");
        }
        if (status != TaskStatus.DONE && completedAt != null) {
            throw new IllegalArgumentException("active task must not have completedAt");
        }
    }

    public PersonalTask(UUID id, String ownerId, String conversationId, TaskKind kind, String title,
            String description, TaskStatus status, UUID parentId, Instant dueAt, ZoneId timezone,
            String nextAction, String waitingFor, Instant followUpAt, Instant lastFollowUpAt,
            Instant createdAt, Instant updatedAt, Instant completedAt) {
        this(id, ownerId, conversationId, kind, title, description, status, parentId, null, dueAt, timezone,
                nextAction, waitingFor, followUpAt, lastFollowUpAt, createdAt, updatedAt, completedAt);
    }

    public boolean active() {
        return status.active();
    }

    private static String requireOwner(String value) {
        if (value == null || value.isBlank() || "*".equals(value)) {
            throw new IllegalArgumentException("owner id must not be blank or wildcard");
        }
        return value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
