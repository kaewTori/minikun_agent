package com.minikun.model;

import java.util.Objects;

/** Stable description of a collaborator; runtime output belongs in PeerResult. */
public record FriendCard(
        String id,
        String name,
        String role,
        String strengths,
        String limitations,
        int priority) {

    public FriendCard {
        id = required(id, "id");
        name = required(name, "name");
        role = required(role, "role");
        strengths = required(strengths, "strengths");
        limitations = required(limitations, "limitations");
        if (priority < 1) throw new IllegalArgumentException("priority must be positive");
    }

    public String promptDescription() {
        return "%s (%s): role=%s; strengths=%s; limitations=%s"
                .formatted(name, id, role, strengths, limitations);
    }

    private static String required(String value, String field) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
