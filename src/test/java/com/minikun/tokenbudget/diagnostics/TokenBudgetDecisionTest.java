package com.minikun.tokenbudget.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenBudgetDecisionTest {
    @Test
    void acceptsValidDecision() {
        TokenBudgetDecision decision = new TokenBudgetDecision(100, 500, false, true, false);

        assertEquals(100, decision.inputTokens());
        assertEquals(500, decision.allocatedOutputTokens());
    }

    @Test
    void rejectsNegativeInputTokens() {
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBudgetDecision(-1, 500, false, false, false));
    }

    @Test
    void rejectsNegativeAllocatedOutputTokens() {
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBudgetDecision(100, -1, false, false, false));
    }
}