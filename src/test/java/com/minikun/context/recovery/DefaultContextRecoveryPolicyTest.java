package com.minikun.context.recovery;

import com.minikun.tokenbudget.pressure.ContextPressureLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultContextRecoveryPolicyTest {
    private final ContextRecoveryPolicy policy = new DefaultContextRecoveryPolicy();

    @Test
    void leavesNormalContextUntouched() {
        ContextRecoveryDecision decision = policy.decide(24_000, ContextPressureLevel.NORMAL);

        assertFalse(decision.required());
        assertEquals(24_000, decision.targetContextCharacters());
    }

    @Test
    void reducesContextForWarningPressure() {
        ContextRecoveryDecision decision = policy.decide(24_000, ContextPressureLevel.WARNING);

        assertTrue(decision.required());
        assertEquals(18_000, decision.targetContextCharacters());
    }

    @Test
    void doesNotReduceBelowMinimumRecoveryBudget() {
        ContextRecoveryDecision decision = policy.decide(9_000, ContextPressureLevel.CRITICAL);

        assertTrue(decision.required());
        assertEquals(8_000, decision.targetContextCharacters());
    }
}
