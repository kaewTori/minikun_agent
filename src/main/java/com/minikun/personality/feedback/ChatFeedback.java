package com.minikun.personality.feedback;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record ChatFeedback(UUID id, String ownerId, String conversationId, String messageId,
        String rating, ChatFeedbackCategory category, String reason, Instant createdAt) {
    public ChatFeedback {
        id = Objects.requireNonNull(id);
        ownerId = required(ownerId, "owner id");
        conversationId = required(conversationId, "conversation id");
        messageId = required(messageId, "message id");
        rating = required(rating, "rating").toUpperCase(Locale.ROOT);
        if (!rating.equals("UP") && !rating.equals("DOWN")) throw new IllegalArgumentException("rating must be UP or DOWN");
        category = Objects.requireNonNullElse(category, ChatFeedbackCategory.OTHER);
        reason = Objects.requireNonNullElse(reason, "").trim();
        if (reason.length() > 500) reason = reason.substring(0, 500).stripTrailing();
        createdAt = Objects.requireNonNull(createdAt);
    }
    private static String required(String value, String field) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isBlank() || "*".equals(normalized)) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
