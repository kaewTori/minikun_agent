package com.minikun.context.recovery;

import com.minikun.tokenbudget.pressure.ContextPressureLevel;

import java.util.Objects;

public final class DefaultContextRecoveryPolicy implements ContextRecoveryPolicy {
    private final long minimumContextCharacters;
    private final long recoveryPercent;

    public DefaultContextRecoveryPolicy() {
        this(8_000L, 75L);
    }

    public DefaultContextRecoveryPolicy(long minimumContextCharacters, long recoveryPercent) {
        if (minimumContextCharacters < 0) {
            throw new IllegalArgumentException("minimum context characters must not be negative");
        }
        if (recoveryPercent < 1 || recoveryPercent > 99) {
            throw new IllegalArgumentException("recovery percent must be between 1 and 99");
        }
        this.minimumContextCharacters = minimumContextCharacters;
        this.recoveryPercent = recoveryPercent;
    }

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
                minimumContextCharacters,
                currentContextCharacters * recoveryPercent / 100L);
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
