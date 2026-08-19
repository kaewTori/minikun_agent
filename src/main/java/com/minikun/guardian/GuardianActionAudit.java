package com.minikun.guardian;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Owner-scoped audit record for every requested or executed remediation. */
public record GuardianActionAudit(
        UUID id,
        String ownerId,
        String conversationId,
        String actionId,
        String status,
        Instant createdAt,
        String detail) {

    public GuardianActionAudit {
        id = Objects.requireNonNull(id, "guardian audit id must not be null");
        ownerId = required(ownerId, "guardian audit owner");
        conversationId = required(conversationId, "guardian audit conversation");
        actionId = required(actionId, "guardian audit action");
        status = required(status, "guardian audit status");
        createdAt = Objects.requireNonNull(createdAt, "guardian audit time must not be null");
        detail = Objects.requireNonNullElse(detail, "").trim();
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
