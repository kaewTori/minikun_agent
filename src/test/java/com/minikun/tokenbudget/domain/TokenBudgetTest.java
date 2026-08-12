package com.minikun.tokenbudget.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenBudgetTest {
    @Test
    void acceptsZeroAndMaximumValues() {
        TokenBudget budget = new TokenBudget(Long.MAX_VALUE, 0, Long.MAX_VALUE);

        assertEquals(Long.MAX_VALUE, budget.maxContextTokens());
        assertEquals(0, budget.reservedOutputTokens());
        assertEquals(Long.MAX_VALUE, budget.applicationMaxOutputTokens());
    }

    @Test
    void rejectsNegativeValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBudget(-1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBudget(0, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBudget(0, 0, -1));
    }
}
