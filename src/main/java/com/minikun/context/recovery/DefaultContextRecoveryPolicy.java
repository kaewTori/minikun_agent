package com.minikun.context.recovery;

import com.minikun.tokenbudget.pressure.ContextPressureLevel;

import java.util.Objects;

public final class DefaultContextRecoveryPolicy implements ContextRecoveryPolicy {
    private static final long MINIMUM_CONTEXT_CHARACTERS = 8_000L;
    private static final long RECOVERY_PERCENT = 75L;

    @Override
    public ContextRecoveryDecision decide(long currentContextCharacters, ContextPressureLevel pressureLevel) {
        if (currentContextCharacters < 0) {
            throw new IllegalArgumentException("current context characters must not be negative");
        }
        Objects.requireNonNull(pressureLevel, "pressure level must not be null");
        if (pressureLevel == ContextPressureLevel.NORMAL) {
            return ContextRecoveryDecision.none(currentContextCharacters);
        }
        long reduced = Math.max(
                MINIMUM_CONTEXT_CHARACTERS,
                currentContextCharacters * RECOVERY_PERCENT / 100L);
        if (reduced >= currentContextCharacters) {
            return ContextRecoveryDecision.none(currentContextCharacters);
        }
        return new ContextRecoveryDecision(
                true,
                reduced,
                pressureLevel == ContextPressureLevel.CRITICAL
                        ? "critical_output_pressure"
                        : "warning_output_pressure");
    }
}
