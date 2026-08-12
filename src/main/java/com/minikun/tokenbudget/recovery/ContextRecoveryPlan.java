package com.minikun.tokenbudget.recovery;

import java.util.Objects;

public record ContextRecoveryPlan(
        RecoveryPriority priority,
        RecoveryTarget target,
        boolean required) {
    public ContextRecoveryPlan {
        Objects.requireNonNull(priority, "recovery priority must not be null");
        Objects.requireNonNull(target, "recovery target must not be null");
    }
}