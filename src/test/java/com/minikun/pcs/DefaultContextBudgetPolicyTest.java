package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultContextBudgetPolicyTest {
    @Test
    void defaultPolicyAllocatesEverySectionDeterministically() {
        DefaultContextBudgetPolicy policy = new DefaultContextBudgetPolicy();
        ContextBudget first = policy.allocate(101);
        ContextBudget second = policy.allocate(101);

        assertEquals(first, second);
        assertEquals(ContextBudgetSection.values().length, first.allocations().size());
        assertEquals(101, first.allocated());
        assertEquals(0, first.remaining());
    }

    @Test
    void zeroAndVerySmallBudgetsRemainValid() {
        DefaultContextBudgetPolicy policy = new DefaultContextBudgetPolicy();

        ContextBudget zero = policy.allocate(0);
        ContextBudget small = policy.allocate(1);

        assertEquals(0, zero.allocated());
        assertEquals(0, zero.remaining());
        assertEquals(1, small.allocated());
        assertEquals(0, small.remaining());
    }

    @Test
    void exactWeightedBudgetHasNoRemainder() {
        DefaultContextBudgetPolicy policy = new DefaultContextBudgetPolicy();
        ContextBudget budget = policy.allocate(100);

        assertEquals(15, budget.allocation(ContextBudgetSection.CHARACTER));
        assertEquals(10, budget.allocation(ContextBudgetSection.RUNTIME));
        assertEquals(30, budget.allocation(ContextBudgetSection.CONVERSATION));
        assertEquals(5, budget.allocation(ContextBudgetSection.USER_MODEL));
        assertEquals(10, budget.allocation(ContextBudgetSection.MEMORY));
        assertEquals(5, budget.allocation(ContextBudgetSection.KNOWLEDGE));
        assertEquals(5, budget.allocation(ContextBudgetSection.CAPABILITIES));
        assertEquals(20, budget.allocation(ContextBudgetSection.USER_MESSAGE));
    }

    @Test
    void equalRemaindersUseStableSectionOrder() {
        DefaultContextBudgetPolicy policy = new DefaultContextBudgetPolicy(completeWeights(1L));

        ContextBudget budget = policy.allocate(1);

        assertEquals(1, budget.allocation(ContextBudgetSection.CHARACTER));
        assertEquals(0, budget.allocation(ContextBudgetSection.RUNTIME));
        assertEquals(1, budget.allocated());
    }

    @Test
    void weightsAreDefensiveAndMustBeCompleteAndPositiveInTotal() {
        DefaultContextBudgetPolicy policy = new DefaultContextBudgetPolicy();
        assertThrows(UnsupportedOperationException.class,
                () -> policy.weights().put(ContextBudgetSection.MEMORY, 1L));

        EnumMap<ContextBudgetSection, Long> incomplete = new EnumMap<>(ContextBudgetSection.class);
        incomplete.put(ContextBudgetSection.CHARACTER, 1L);
        assertThrows(IllegalArgumentException.class, () -> new DefaultContextBudgetPolicy(incomplete));

        Map<ContextBudgetSection, Long> negative = completeWeights(-1L);
        assertThrows(IllegalArgumentException.class, () -> new DefaultContextBudgetPolicy(negative));
        assertThrows(IllegalArgumentException.class,
                () -> new DefaultContextBudgetPolicy(completeWeights(0L)));
    }

    private Map<ContextBudgetSection, Long> completeWeights(long weight) {
        EnumMap<ContextBudgetSection, Long> weights = new EnumMap<>(ContextBudgetSection.class);
        for (ContextBudgetSection section : ContextBudgetSection.values()) {
            weights.put(section, weight);
        }
        return weights;
    }
}
