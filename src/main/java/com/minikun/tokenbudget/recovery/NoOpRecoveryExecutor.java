package com.minikun.tokenbudget.recovery;

import java.util.Objects;

public final class NoOpRecoveryExecutor implements RecoveryExecutor {
    @Override
    public RecoveryExecutionResult execute(RecoveryPriorityPlan plan) {
        Objects.requireNonNull(plan, "recovery priority plan must not be null");
        RecoveryExecutionStatus status = plan.steps().isEmpty()
                ? RecoveryExecutionStatus.NOT_REQUIRED
                : RecoveryExecutionStatus.PLANNED;
        return new RecoveryExecutionResult(status, plan, false);
    }
}