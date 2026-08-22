package com.minikun.task;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/** Nullable fields for an owner-authorized task update. */
public record TaskPatch(
        String title,
        String description,
        TaskStatus status,
        Instant dueAt,
        ZoneId timezone,
        String nextAction,
        String waitingFor,
        Instant followUpAt,
        UUID goalId) {
    public TaskPatch(String title, String description, TaskStatus status, Instant dueAt, ZoneId timezone,
            String nextAction, String waitingFor, Instant followUpAt) {
        this(title, description, status, dueAt, timezone, nextAction, waitingFor, followUpAt, null);
    }
}
