package com.minikun.tokenbudget.recovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NoOpRecoveryStrategyHandlerTest {
    private final RecoveryStrategyHandler handler = new NoOpRecoveryStrategyHandler();

    @Test
    void returnsNotExecutedForEveryRecoveryStep() {
        for (RecoveryStep step : RecoveryStep.values()) {
            RecoveryExecutionResult result = handler.execute(step);

            assertEquals(RecoveryExecutionStatus.NOT_EXECUTED, result.status());
            assertEquals(java.util.List.of(step), result.plan().steps());
            assertFalse(result.changed());
        }
    }

    @Test
    void rejectsNullStep() {
        assertThrows(NullPointerException.class, () -> handler.execute(null));
    }
}