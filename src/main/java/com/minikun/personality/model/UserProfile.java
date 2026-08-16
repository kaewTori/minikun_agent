package com.minikun.personality.model;

import java.util.Objects;

/** Stable, user-approved facts used to personalize the agent. */
public record UserProfile(
        String ownerId,
        String displayName,
        String preferredLanguage,
        String responseStyle,
        String timezone) {
    public static final UserProfile EMPTY = new UserProfile("default", "", "", "", "");

    public UserProfile {
        ownerId = require(ownerId, "ownerId");
        displayName = normalize(displayName);
        preferredLanguage = normalize(preferredLanguage);
        responseStyle = normalize(responseStyle);
        timezone = normalize(timezone);
    }

    public boolean available() {
        return !displayName.isBlank() || !preferredLanguage.isBlank()
                || !responseStyle.isBlank() || !timezone.isBlank();
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String normalize(String value) { return Objects.requireNonNullElse(value, "").trim(); }
}
