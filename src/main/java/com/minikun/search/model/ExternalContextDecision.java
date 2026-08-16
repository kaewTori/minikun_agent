package com.minikun.search.model;

import java.util.Objects;

public record ExternalContextDecision(
        ExternalContextAction action,
        SearchDecision searchDecision,
        boolean explicitUrl,
        boolean conversationContextAvailable,
        double confidence,
        String reason) {
    public ExternalContextDecision {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(searchDecision, "search decision must not be null");
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        reason = reason == null ? "" : reason;
    }
}
