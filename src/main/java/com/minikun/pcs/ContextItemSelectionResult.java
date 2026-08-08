package com.minikun.pcs;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ContextItemSelectionResult(
        List<ContextItem> selectedItems,
        List<ContextEviction> evictions,
        List<ContextItem> requiredOverflowItems,
        Map<ContextBudgetSection, Long> usage,
        Map<ContextBudgetSection, Long> remaining,
        Map<ContextBudgetSection, Long> overflow,
        boolean requiredOverflow) {
    public ContextItemSelectionResult {
        selectedItems = List.copyOf(Objects.requireNonNull(selectedItems, "selected items must not be null"));
        evictions = List.copyOf(Objects.requireNonNull(evictions, "evictions must not be null"));
        requiredOverflowItems = List.copyOf(
                Objects.requireNonNull(requiredOverflowItems, "required overflow items must not be null"));
        usage = snapshotValues(usage, "usage");
        remaining = snapshotValues(remaining, "remaining");
        overflow = snapshotValues(overflow, "overflow");
        if (!requiredOverflow && overflow.values().stream().anyMatch(value -> value > 0)) {
            throw new IllegalArgumentException("overflow requires required overflow status");
        }
        if (requiredOverflow && requiredOverflowItems.isEmpty()) {
            throw new IllegalArgumentException("required overflow must identify required items");
        }
    }

    public List<ContextEviction> diagnostics() {
        List<ContextEviction> diagnostics = new ArrayList<>(evictions);
        diagnostics.addAll(requiredOverflowItems.stream()
                .map(item -> new ContextEviction(item, ContextEvictionReason.REQUIRED_OVERFLOW))
                .toList());
        return List.copyOf(diagnostics);
    }

    public List<ContextItem> evictedItems() {
        return evictions.stream().map(ContextEviction::item).toList();
    }

    public long usage(ContextBudgetSection section) {
        Objects.requireNonNull(section, "section must not be null");
        return usage.getOrDefault(section, 0L);
    }

    public long remaining(ContextBudgetSection section) {
        Objects.requireNonNull(section, "section must not be null");
        return remaining.getOrDefault(section, 0L);
    }

    public long overflow(ContextBudgetSection section) {
        Objects.requireNonNull(section, "section must not be null");
        return overflow.getOrDefault(section, 0L);
    }

    private static Map<ContextBudgetSection, Long> snapshotValues(
            Map<ContextBudgetSection, Long> values,
            String name) {
        Objects.requireNonNull(values, name + " must not be null");
        EnumMap<ContextBudgetSection, Long> snapshot = new EnumMap<>(ContextBudgetSection.class);
        values.forEach((section, value) -> {
            Objects.requireNonNull(section, name + " section must not be null");
            Objects.requireNonNull(value, name + " value must not be null");
            if (value < 0) {
                throw new IllegalArgumentException(name + " values must not be negative");
            }
            snapshot.put(section, value);
        });
        return Map.copyOf(snapshot);
    }
}