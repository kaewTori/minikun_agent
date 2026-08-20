package com.minikun.communication;

import java.util.Locale;

public enum CommunicationAction {
    DRAFT,
    REWRITE,
    REPLY,
    SUMMARIZE;

    public static CommunicationAction parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("action is required");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("action must be draft, rewrite, reply, or summarize");
        }
    }
}
