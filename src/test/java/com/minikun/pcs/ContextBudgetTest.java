package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextBudgetTest {
    @Test
    void unitsAndSectionsHaveStableDefinitions() {
        assertEquals(ContextBudgetUnit.CHARACTERS, ContextBudgetUnit.values()[0]);
        assertIterableEquals(List.of(
                ContextBudgetSection.CHARACTER,
                ContextBudgetSection.RUNTIME,
                ContextBudgetSection.CONVERSATION,
                ContextBudgetSection.USER_MODEL,
                ContextBudgetSection.MEMORY,
                ContextBudgetSection.KNOWLEDGE,
                ContextBudgetSection.CAPABILITIES,
                ContextBudgetSection.USER_MESSAGE),
                List.of(ContextBudgetSection.values()));
    }

    @Test
    void allocationValidatesSectionAndAmount() {
        assertEquals(new ContextBudgetAllocation(ContextBudgetSection.MEMORY, 0),
                new ContextBudgetAllocation(ContextBudgetSection.MEMORY, 0));
        assertThrows(NullPointerException.class,
                () -> new ContextBudgetAllocation(null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextBudgetAllocation(ContextBudgetSection.MEMORY, -1));
    }

    @Test
    void budgetCalculatesAllocationAndRemaining() {
        ContextBudget budget = new ContextBudget(ContextBudgetUnit.CHARACTERS, 100,
                List.of(
                        new ContextBudgetAllocation(ContextBudgetSection.CHARACTER, 25),
                        new ContextBudgetAllocation(ContextBudgetSection.MEMORY, 50)));

        assertEquals(ContextBudgetUnit.CHARACTERS, budget.unit());
        assertEquals(100, budget.total());
        assertEquals(75, budget.allocated());
        assertEquals(25, budget.remaining());
        assertEquals(50, budget.allocation(ContextBudgetSection.MEMORY));
        assertEquals(0, budget.allocation(ContextBudgetSection.KNOWLEDGE));
    }

    @Test
    void zeroBudgetAndExactAllocationAreValid() {
        ContextBudget zero = new ContextBudget(ContextBudgetUnit.CHARACTERS, 0, List.of());
        ContextBudget exact = new ContextBudget(ContextBudgetUnit.CHARACTERS, 10,
                List.of(new ContextBudgetAllocation(ContextBudgetSection.RUNTIME, 10)));

        assertEquals(0, zero.remaining());
        assertEquals(0, exact.remaining());
    }

    @Test
    void invalidBudgetStateIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new ContextBudget(null, 1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextBudget(ContextBudgetUnit.CHARACTERS, -1, List.of()));
        assertThrows(NullPointerException.class,
                () -> new ContextBudget(ContextBudgetUnit.CHARACTERS, 1, null));
        assertThrows(NullPointerException.class,
                () -> new ContextBudget(ContextBudgetUnit.CHARACTERS, 1, List.of((ContextBudgetAllocation) null)));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextBudget(ContextBudgetUnit.CHARACTERS, 1,
                        List.of(new ContextBudgetAllocation(ContextBudgetSection.RUNTIME, 2))));
        ContextBudgetAllocation allocation = new ContextBudgetAllocation(ContextBudgetSection.RUNTIME, 1);
        assertThrows(IllegalArgumentException.class,
                () -> new ContextBudget(ContextBudgetUnit.CHARACTERS, 2,
                        List.of(allocation, allocation)));
    }

    @Test
    void budgetSnapshotsAndExposesImmutableAllocations() {
        List<ContextBudgetAllocation> source = new ArrayList<>();
        source.add(new ContextBudgetAllocation(ContextBudgetSection.MEMORY, 2));
        ContextBudget budget = new ContextBudget(ContextBudgetUnit.CHARACTERS, 2, source);
        source.clear();

        assertEquals(1, budget.allocations().size());
        assertThrows(UnsupportedOperationException.class,
                () -> budget.allocations().add(new ContextBudgetAllocation(ContextBudgetSection.RUNTIME, 0)));
    }

    @Test
    void missingSectionLookupRejectsNull() {
        ContextBudget budget = new ContextBudget(ContextBudgetUnit.CHARACTERS, 1, List.of());
        assertThrows(NullPointerException.class, () -> budget.allocation(null));
        assertTrue(budget.allocations().isEmpty());
    }
}
