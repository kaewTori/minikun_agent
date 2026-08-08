package com.minikun.pcs;

import java.util.Objects;

public record ContextBudgetAllocation(ContextBudgetSection section, long amount) {
    public ContextBudgetAllocation {
        Objects.requireNonNull(section, "section must not be null");
        if (amount < 0) {
            throw new IllegalArgumentException("allocation amount must not be negative");
        }
    }
}
