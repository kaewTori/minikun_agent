package com.minikun.tokenbudget.pressure;

import java.util.Objects;

public final class DefaultContextPressureRecoveryPolicy implements ContextPressureRecoveryPolicy {
    @Override
    public RecoveryAction decide(ContextPressureDecision decision) {
        Objects.requireNonNull(decision, "context pressure decision must not be null");
        return switch (decision.level()) {
            case NORMAL -> RecoveryAction.NONE;
            case WARNING -> RecoveryAction.REDUCE_CONTEXT;
            case CRITICAL -> RecoveryAction.USE_FALLBACK;
        };
    }
}