package com.minikun.tokenbudget.recovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

class RecoveryExecutionResultTest {
    private static final RecoveryPriorityPlan PLAN =
            new RecoveryPriorityPlan(List.of(RecoveryStep.REDUCE_KNOWLEDGE));

    @Test
    void rejectsNullStatus() {
        assertThrows(NullPointerException.class,
                () -> new RecoveryExecutionResult(null, PLAN, false));
    }

    @Test
    void rejectsNullPlan() {
        assertThrows(NullPointerException.class,
                () -> new RecoveryExecutionResult(RecoveryExecutionStatus.PLANNED, null, false));
    }

    @Test
    void preservesTheProvidedPlan() {
        RecoveryExecutionResult result = new RecoveryExecutionResult(
                RecoveryExecutionStatus.PLANNED, PLAN, false);

        assertSame(PLAN, result.plan());
    }
}