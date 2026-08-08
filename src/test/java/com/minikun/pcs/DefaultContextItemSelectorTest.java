package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultContextItemSelectorTest {
    private final ContextItemSelector selector = new DefaultContextItemSelector();

    @Test
    void allItemsFitAndSelectedOutputPreservesInputOrder() {
        ContextItem first = item(ContextBudgetSection.MEMORY, "first", 1, false);
        ContextItem second = item(ContextBudgetSection.MEMORY, "second", 3, false);
        ContextBudget budget = budget(ContextBudgetSection.MEMORY, 11);

        ContextItemSelectionResult result = selector.select(budget, List.of(first, second));

        assertEquals(List.of(first, second), result.selectedItems());
        assertTrue(result.evictions().isEmpty());
        assertEquals(11, result.usage(ContextBudgetSection.MEMORY));
        assertEquals(0, result.remaining(ContextBudgetSection.MEMORY));
        assertEquals(0, result.overflow(ContextBudgetSection.MEMORY));
        assertFalse(result.requiredOverflow());
    }

    @Test
    void optionalItemsUseDescendingPriorityAndStableInputOrderForTies() {
        ContextItem low = item(ContextBudgetSection.MEMORY, "low", 1, false);
        ContextItem high = item(ContextBudgetSection.MEMORY, "high", 3, false);
        ContextItem tied = item(ContextBudgetSection.MEMORY, "tied", 3, false);
        ContextBudget budget = budget(ContextBudgetSection.MEMORY, 8);

        ContextItemSelectionResult result = selector.select(budget, List.of(low, high, tied));

        assertEquals(List.of(high, tied), result.selectedItems());
        assertEquals(List.of(low), result.evictions().stream().map(ContextEviction::item).toList());
        assertEquals(ContextEvictionReason.OVER_BUDGET, result.evictions().get(0).reason());
    }

    @Test
    void requiredItemsArePreservedBeforeOptionalItems() {
        ContextItem required = item(ContextBudgetSection.MEMORY, "required", 0, true);
        ContextItem optional = item(ContextBudgetSection.MEMORY, "optional", 9, false);

        ContextItemSelectionResult result = selector.select(budget(ContextBudgetSection.MEMORY, 8),
                List.of(required, optional));

        assertEquals(List.of(required), result.selectedItems());
        assertEquals(List.of(optional), result.evictions().stream().map(ContextEviction::item).toList());
        assertFalse(result.requiredOverflow());
    }

    @Test
    void requiredOverflowPreservesRequiredItemsAndSeparatesOverflowFromRemaining() {
        ContextItem required = item(ContextBudgetSection.MEMORY, "12345", 0, true);
        ContextItem optional = item(ContextBudgetSection.MEMORY, "ok", 9, false);
        ContextBudget budget = budget(ContextBudgetSection.MEMORY, 3);

        ContextItemSelectionResult result = selector.select(budget, List.of(required, optional));

        assertEquals(List.of(required), result.selectedItems());
        assertEquals(List.of(optional), result.evictions().stream().map(ContextEviction::item).toList());
        assertEquals(List.of(required), result.requiredOverflowItems());
        assertEquals(5, result.usage(ContextBudgetSection.MEMORY));
        assertEquals(0, result.remaining(ContextBudgetSection.MEMORY));
        assertEquals(2, result.overflow(ContextBudgetSection.MEMORY));
        assertTrue(result.requiredOverflow());
        assertEquals(ContextEvictionReason.REQUIRED_OVERFLOW, result.diagnostics().get(1).reason());
        assertEquals(3, budget.allocation(ContextBudgetSection.MEMORY));
        assertEquals(0, budget.remaining());
    }

    @Test
    void sectionsHaveIndependentBudgets() {
        ContextItem memory = item(ContextBudgetSection.MEMORY, "memory", 1, false);
        ContextItem knowledge = item(ContextBudgetSection.KNOWLEDGE, "knowledge", 1, false);
        ContextBudget budget = new ContextBudget(ContextBudgetUnit.CHARACTERS, 9, List.of(
                new ContextBudgetAllocation(ContextBudgetSection.MEMORY, 6),
                new ContextBudgetAllocation(ContextBudgetSection.KNOWLEDGE, 3)));

        ContextItemSelectionResult result = selector.select(budget, List.of(memory, knowledge));

        assertEquals(List.of(memory), result.selectedItems());
        assertEquals(6, result.usage(ContextBudgetSection.MEMORY));
        assertEquals(0, result.usage(ContextBudgetSection.KNOWLEDGE));
        assertEquals(ContextEvictionReason.OVER_BUDGET, result.evictions().get(0).reason());
    }

    @Test
    void repeatedSelectionIsDeterministic() {
        List<ContextItem> items = List.of(
                item(ContextBudgetSection.MEMORY, "one", 1, false),
                item(ContextBudgetSection.MEMORY, "two", 3, false),
                item(ContextBudgetSection.MEMORY, "three", 2, false));
        ContextBudget budget = budget(ContextBudgetSection.MEMORY, 6);

        ContextItemSelectionResult first = selector.select(budget, items);
        ContextItemSelectionResult second = selector.select(budget, items);

        assertEquals(first, second);
    }

    @Test
    void resultCollectionsAreImmutableAndInputIsSnapshotted() {
        List<ContextItem> input = new ArrayList<>();
        ContextItem item = item(ContextBudgetSection.MEMORY, "value", 1, false);
        input.add(item);
        ContextItemSelectionResult result = selector.select(budget(ContextBudgetSection.MEMORY, 10), input);
        input.clear();

        assertEquals(List.of(item), result.selectedItems());
        assertThrows(UnsupportedOperationException.class, () -> result.selectedItems().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.usage().put(ContextBudgetSection.MEMORY, 0L));
        assertThrows(UnsupportedOperationException.class, () -> result.remaining().put(ContextBudgetSection.MEMORY, 0L));
        assertThrows(UnsupportedOperationException.class, () -> result.overflow().put(ContextBudgetSection.MEMORY, 0L));
    }

    @Test
    void emptyAndZeroBudgetInputsAreValid() {
        ContextItemSelectionResult empty = selector.select(
                new ContextBudget(ContextBudgetUnit.CHARACTERS, 0, List.of()), List.of());
        ContextItem zeroSized = item(ContextBudgetSection.RUNTIME, "", 1, false);
        ContextItemSelectionResult zero = selector.select(
                new ContextBudget(ContextBudgetUnit.CHARACTERS, 0, List.of()), List.of(zeroSized));

        assertTrue(empty.selectedItems().isEmpty());
        assertEquals(List.of(zeroSized), zero.selectedItems());
        assertEquals(0, zero.usage(ContextBudgetSection.RUNTIME));
    }

    @Test
    void nullBudgetItemsAndNonCharacterBudgetAreRejected() {
        assertThrows(NullPointerException.class, () -> selector.select(null, List.of()));
        assertThrows(NullPointerException.class, () -> selector.select(
                budget(ContextBudgetSection.MEMORY, 1), null));
        assertThrows(NullPointerException.class, () -> selector.select(
                budget(ContextBudgetSection.MEMORY, 1), List.of((ContextItem) null)));
    }

    private ContextItem item(ContextBudgetSection section, String content, int priority, boolean required) {
        return new ContextItem(section, content, priority, required);
    }

    private ContextBudget budget(ContextBudgetSection section, long amount) {
        return new ContextBudget(ContextBudgetUnit.CHARACTERS, amount,
                List.of(new ContextBudgetAllocation(section, amount)));
    }
}