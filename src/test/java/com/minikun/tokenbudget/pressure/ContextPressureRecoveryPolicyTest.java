package com.minikun.tokenbudget.pressure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextPressureRecoveryPolicyTest {
    private final ContextPressureRecoveryPolicy policy = new DefaultContextPressureRecoveryPolicy();

    @Test
    void normalPressureNeedsNoRecovery() {
        assertEquals(RecoveryAction.NONE, policy.decide(decision(ContextPressureLevel.NORMAL, false)));
    }

    @Test
    void warningPressureRecommendsContextReduction() {
        assertEquals(RecoveryAction.REDUCE_CONTEXT,
                policy.decide(decision(ContextPressureLevel.WARNING, true)));
    }

    @Test
    void criticalPressureRecommendsFallback() {
        assertEquals(RecoveryAction.USE_FALLBACK,
                policy.decide(decision(ContextPressureLevel.CRITICAL, true)));
    }

    @Test
    void rejectsNullDecision() {
        assertThrows(NullPointerException.class, () -> policy.decide(null));
    }

    private ContextPressureDecision decision(ContextPressureLevel level, boolean requiresRecovery) {
        return new ContextPressureDecision(500, 500, level, requiresRecovery);
    }
}