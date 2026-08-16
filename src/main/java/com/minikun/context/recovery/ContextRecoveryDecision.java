package com.minikun.context.recovery;

import java.util.Objects;

public record ContextRecoveryDecision(
        boolean required,
        long targetContextCharacters,
        String reason) {
    public ContextRecoveryDecision {
        if (targetContextCharacters < 0) {
            throw new IllegalArgumentException("target context characters must not be negative");
        }
        Objects.requireNonNull(reason, "recovery reason must not be null");
    }

    public static ContextRecoveryDecision none(long currentContextCharacters) {
        return new ContextRecoveryDecision(false, currentContextCharacters, "within_budget");
    }
}
