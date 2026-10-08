package com.minikun.notification;

import java.util.Objects;

/** A notification with stable source metadata for retries and delivery history. */
public record NotificationRequest(
        String sourceType,
        String sourceId,
        NotificationChannel channel,
        String title,
        String message,
        int priority,
        String tags,
        String clickUrl) {

    public NotificationRequest(String sourceType, String sourceId, NotificationChannel channel,
            String title, String message, int priority, String tags) {
        this(sourceType, sourceId, channel, title, message, priority, tags, "");
    }

    public NotificationRequest {
        sourceType = required(sourceType, "notification source type").toUpperCase(java.util.Locale.ROOT);
        sourceId = required(sourceId, "notification source id");
        channel = Objects.requireNonNull(channel, "notification channel must not be null");
        title = required(title, "notification title");
        message = required(message, "notification message");
        priority = Math.max(1, Math.min(5, priority));
        tags = Objects.requireNonNullElse(tags, "").trim();
        clickUrl = Objects.requireNonNullElse(clickUrl, "").trim();
        if (!clickUrl.isBlank()) {
            java.net.URI uri = java.net.URI.create(clickUrl);
            if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || clickUrl.matches(".*[,;\\r\\n].*"))
                throw new IllegalArgumentException("notification click URL must be a plain HTTP(S) URL");
            clickUrl = uri.toASCIIString();
        }
    }

    private static String required(String value, String name) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
