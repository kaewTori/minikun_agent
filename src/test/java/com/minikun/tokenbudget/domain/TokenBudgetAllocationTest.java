package com.minikun.tokenbudget.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenBudgetAllocationTest {
    @Test
    void acceptsZeroAndMaximumValues() {
        TokenBudgetAllocation allocation = new TokenBudgetAllocation(Long.MAX_VALUE, Long.MAX_VALUE, true);

        assertEquals(Long.MAX_VALUE, allocation.inputTokens());
        assertEquals(Long.MAX_VALUE, allocation.availableOutputTokens());
    }

    @Test
    void rejectsNegativeTokenValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBudgetAllocation(-1, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBudgetAllocation(0, -1, false));
    }
}
