package com.minikun.tokenbudget.policy;

import com.minikun.tokenbudget.domain.TokenBudget;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTokenBudgetPolicyTest {
    private final TokenBudgetPolicy policy = new DefaultTokenBudgetPolicy();

    @Test
    void allocatesRemainingOutputWhenInputFits() {
        TokenBudget budget = new TokenBudget(4096, 512, 4096);

        TokenBudgetAllocation allocation = policy.allocate(budget, 1000);

        assertEquals(1000, allocation.inputTokens());
        assertEquals(2584, allocation.availableOutputTokens());
        assertFalse(allocation.truncated());
    }

    @Test
    void capsOutputAtApplicationMaximum() {
        TokenBudget budget = new TokenBudget(4096, 512, 2048);

        TokenBudgetAllocation allocation = policy.allocate(budget, 1000);

        assertEquals(2048, allocation.availableOutputTokens());
        assertFalse(allocation.truncated());
    }

    @Test
    void marksAllocationTruncatedWhenInputConsumesContext() {
        TokenBudget budget = new TokenBudget(4096, 0, 2048);

        TokenBudgetAllocation allocation = policy.allocate(budget, 4096);

        assertEquals(0, allocation.availableOutputTokens());
        assertTrue(allocation.truncated());
    }

    @Test
    void marksAllocationTruncatedWhenReservedOutputExceedsRemainingContext() {
        TokenBudget budget = new TokenBudget(4096, 512, 2048);

        TokenBudgetAllocation allocation = policy.allocate(budget, 4096);

        assertEquals(0, allocation.availableOutputTokens());
        assertTrue(allocation.truncated());
    }

    @Test
    void handlesLongMaximumBoundaryWithoutOverflow() {
        TokenBudget budget = new TokenBudget(Long.MAX_VALUE, 0, Long.MAX_VALUE);

        TokenBudgetAllocation allocation = policy.allocate(budget, 0);

        assertEquals(Long.MAX_VALUE, allocation.availableOutputTokens());
        assertFalse(allocation.truncated());
    }

    @Test
    void handlesLongMaximumOverflowBoundaryWithoutNegativeOutput() {
        TokenBudget budget = new TokenBudget(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);

        TokenBudgetAllocation allocation = policy.allocate(budget, Long.MAX_VALUE);

        assertEquals(0, allocation.availableOutputTokens());
        assertTrue(allocation.truncated());
    }

    @Test
    void rejectsInvalidInputs() {
        TokenBudget budget = new TokenBudget(4096, 512, 2048);

        assertThrows(NullPointerException.class, () -> policy.allocate(null, 0));
        assertThrows(IllegalArgumentException.class, () -> policy.allocate(budget, -1));
    }
}
