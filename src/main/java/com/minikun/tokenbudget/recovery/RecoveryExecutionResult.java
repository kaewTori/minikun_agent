package com.minikun.tokenbudget.recovery;

import java.util.Objects;

public record RecoveryExecutionResult(
        RecoveryExecutionStatus status,
        RecoveryPriorityPlan plan,
        boolean changed) {
    public RecoveryExecutionResult {
        Objects.requireNonNull(status, "recovery execution status must not be null");
        Objects.requireNonNull(plan, "recovery priority plan must not be null");
    }
}