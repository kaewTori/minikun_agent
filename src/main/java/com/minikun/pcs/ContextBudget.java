package com.minikun.pcs;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable accounting state for the capacity available to a future context assembly stage.
 * This contract does not trim, evict, summarize, or compress any context.
 */
public record ContextBudget(
        ContextBudgetUnit unit,
        long total,
        List<ContextBudgetAllocation> allocations) {
    public ContextBudget {
        Objects.requireNonNull(unit, "unit must not be null");
        if (total < 0) {
            throw new IllegalArgumentException("total budget must not be negative");
        }
        Objects.requireNonNull(allocations, "allocations must not be null");

        Set<ContextBudgetSection> sections = new HashSet<>();
        long allocated = 0;
        for (ContextBudgetAllocation allocation : allocations) {
            Objects.requireNonNull(allocation, "allocation must not be null");
            if (!sections.add(allocation.section())) {
                throw new IllegalArgumentException("duplicate allocation section: " + allocation.section());
            }
            try {
                allocated = Math.addExact(allocated, allocation.amount());
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("allocated budget exceeds long range", exception);
            }
        }
        if (allocated > total) {
            throw new IllegalArgumentException("allocated budget must not exceed total budget");
        }
        allocations = List.copyOf(allocations);
    }

    public long allocated() {
        long allocated = 0;
        for (ContextBudgetAllocation allocation : allocations) {
            allocated = Math.addExact(allocated, allocation.amount());
        }
        return allocated;
    }

    public long remaining() {
        return total - allocated();
    }

    public long allocation(ContextBudgetSection section) {
        Objects.requireNonNull(section, "section must not be null");
        return allocations.stream()
                .filter(allocation -> allocation.section() == section)
                .mapToLong(ContextBudgetAllocation::amount)
                .findFirst()
                .orElse(0);
    }
}
