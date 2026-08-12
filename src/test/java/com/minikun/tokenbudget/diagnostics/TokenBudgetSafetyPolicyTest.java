package com.minikun.tokenbudget.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBudgetSafetyPolicyTest {
    private final TokenBudgetSafetyPolicy policy = new DefaultTokenBudgetSafetyPolicy();

    @Test
    void doesNotWarnForNormalOutput() {
        assertFalse(policy.shouldWarn(new TokenBudgetDecision(100, 256, false, false, false)));
    }

    @Test
    void warnsWhenOutputIsBelowMinimumThreshold() {
        assertTrue(policy.shouldWarn(new TokenBudgetDecision(100, 255, false, false, false)));
    }

    @Test
    void warnsWhenPromptWasTruncated() {
        assertTrue(policy.shouldWarn(new TokenBudgetDecision(100, 500, true, false, false)));
    }
}