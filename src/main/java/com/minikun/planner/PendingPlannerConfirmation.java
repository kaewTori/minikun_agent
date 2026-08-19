package com.minikun.planner;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A planner write proposal waiting for an explicit user confirmation. */
public record PendingPlannerConfirmation(
        String conversationId,
        String ownerId,
        String action,
        Map<String, Object> arguments,
        Instant createdAt,
        Instant expiresAt) {

    public PendingPlannerConfirmation(
            String conversationId,
            String action,
            Map<String, Object> arguments,
            Instant createdAt,
            Instant expiresAt) {
        this(conversationId, "default", action, arguments, createdAt, expiresAt);
    }

    public PendingPlannerConfirmation {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversation id must not be blank");
        }
        if (ownerId == null || ownerId.isBlank() || "*".equals(ownerId)) {
            throw new IllegalArgumentException("owner id must not be blank or wildcard");
        }
        ownerId = ownerId.trim();
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("planner action must not be blank");
        }
        arguments = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(arguments, "planner confirmation arguments must not be null")));
        Objects.requireNonNull(createdAt, "planner confirmation creation time must not be null");
        Objects.requireNonNull(expiresAt, "planner confirmation expiry time must not be null");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("planner confirmation must expire after creation");
        }
    }
}
