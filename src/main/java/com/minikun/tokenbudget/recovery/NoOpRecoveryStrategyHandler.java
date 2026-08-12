package com.minikun.tokenbudget.recovery;

import java.util.List;
import java.util.Objects;

public final class NoOpRecoveryStrategyHandler implements RecoveryStrategyHandler {
    @Override
    public RecoveryExecutionResult execute(RecoveryStep step) {
        Objects.requireNonNull(step, "recovery step must not be null");
        return new RecoveryExecutionResult(
                RecoveryExecutionStatus.NOT_EXECUTED,
                new RecoveryPriorityPlan(List.of(step)),
                false);
    }
}