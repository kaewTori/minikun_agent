package com.minikun.agent.minikun_agent.conversation;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public record ConversationId(String value) {
    public ConversationId {
        Objects.requireNonNull(value, "conversation id must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("conversation id must not be blank");
        }
    }

    /** Applies the same stable 36-character mapping required by Spring AI JDBC memory. */
    public static ConversationId fromTransport(String identifier) {
        String normalized = Objects.requireNonNull(identifier, "conversation id must not be null").trim();
        if (normalized.length() <= 36 && normalized.chars().noneMatch(Character::isISOControl)) {
            return new ConversationId(normalized);
        }
        return new ConversationId(UUID.nameUUIDFromBytes(normalized.getBytes(StandardCharsets.UTF_8)).toString());
    }
}
