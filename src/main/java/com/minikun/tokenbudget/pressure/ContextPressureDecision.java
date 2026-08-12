package com.minikun.tokenbudget.pressure;

import java.util.Objects;

public record ContextPressureDecision(
        long inputTokens,
        long availableOutputTokens,
        ContextPressureLevel level,
        boolean requiresRecovery) {
    public ContextPressureDecision {
        if (inputTokens < 0) {
            throw new IllegalArgumentException("input tokens must not be negative");
        }
        if (availableOutputTokens < 0) {
            throw new IllegalArgumentException("available output tokens must not be negative");
        }
        Objects.requireNonNull(level, "context pressure level must not be null");
    }
}