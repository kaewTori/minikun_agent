package com.minikun.pcs;

import java.util.List;
import java.util.Objects;

public record ContextProcessingRequest(
        ContextBudget budget,
        List<ContextItem> items) {
    public ContextProcessingRequest {
        Objects.requireNonNull(budget, "budget must not be null");
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }
}
