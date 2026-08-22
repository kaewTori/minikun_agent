package com.minikun.agent.minikun_agent.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Persistent rolling summary for conversation turns that have moved outside the recent window. */
public record ConversationSummary(
        String ownerId,
        ConversationId conversationId,
        String content,
        List<String> coveredFingerprints,
        int summarizedMessages,
        Instant updatedAt) {
    public ConversationSummary {
        ownerId = requireText(ownerId, "owner id");
        Objects.requireNonNull(conversationId, "conversation id must not be null");
        content = requireText(content, "summary content");
        coveredFingerprints = List.copyOf(Objects.requireNonNull(
                coveredFingerprints, "covered fingerprints must not be null"));
        if (summarizedMessages < 0) {
            throw new IllegalArgumentException("summarized messages must not be negative");
        }
        Objects.requireNonNull(updatedAt, "updated at must not be null");
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
