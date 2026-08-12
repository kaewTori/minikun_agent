package com.minikun.tokenbudget.recovery;

import com.minikun.tokenbudget.pressure.ContextPressureDecision;

import java.util.Objects;

public final class DefaultContextRecoveryPlanner implements ContextRecoveryPlanner {
    @Override
    public ContextRecoveryPlan plan(ContextPressureDecision decision) {
        Objects.requireNonNull(decision, "context pressure decision must not be null");
        return switch (decision.level()) {
            case NORMAL -> new ContextRecoveryPlan(
                    RecoveryPriority.LOW, RecoveryTarget.NONE, false);
            case WARNING -> new ContextRecoveryPlan(
                    RecoveryPriority.MEDIUM, RecoveryTarget.KNOWLEDGE_CONTEXT, true);
            case CRITICAL -> new ContextRecoveryPlan(
                    RecoveryPriority.HIGH, RecoveryTarget.FULL_FALLBACK, true);
        };
    }
}