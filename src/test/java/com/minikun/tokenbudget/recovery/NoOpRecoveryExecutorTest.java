package com.minikun.tokenbudget.recovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NoOpRecoveryExecutorTest {
    private final RecoveryExecutor executor = new NoOpRecoveryExecutor();

    @Test
    void reportsNotRequiredForEmptyPlan() {
        RecoveryPriorityPlan plan = new RecoveryPriorityPlan(List.of());
        RecoveryExecutionResult result = executor.execute(plan);

        assertEquals(RecoveryExecutionStatus.NOT_REQUIRED, result.status());
        assertFalse(result.changed());
        assertEquals(plan, result.plan());
    }

    @Test
    void reportsPlannedWithoutChangingForNonEmptyPlan() {
        RecoveryPriorityPlan plan = new RecoveryPriorityPlan(List.of(RecoveryStep.REDUCE_MEMORY));
        RecoveryExecutionResult result = executor.execute(plan);

        assertEquals(RecoveryExecutionStatus.PLANNED, result.status());
        assertFalse(result.changed());
        assertEquals(plan, result.plan());
    }
}