package com.minikun.pcs;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ContextProcessingResult(
        ContextItemSelectionResult selection,
        List<ContextCompressionResult> compressionResults,
        Map<ContextBudgetSection, Long> postCompressionUsage,
        Diagnostics diagnostics) {
    public ContextProcessingResult {
        Objects.requireNonNull(selection, "selection must not be null");
        compressionResults = List.copyOf(
                Objects.requireNonNull(compressionResults, "compression results must not be null"));
        postCompressionUsage = snapshotUsage(postCompressionUsage);
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        if (compressionResults.size() != selection.selectedItems().size()) {
            throw new IllegalArgumentException("compression result count must match selected item count");
        }
        for (int index = 0; index < compressionResults.size(); index++) {
            ContextCompressionResult compressionResult = compressionResults.get(index);
            if (!compressionResult.originalItem().equals(selection.selectedItems().get(index))) {
                throw new IllegalArgumentException("compression results must follow selected item order");
            }
        }
    }

    public List<ContextItem> selectedItems() {
        return compressionResults.stream().map(ContextCompressionResult::compressedItem).toList();
    }

    public List<ContextEviction> evictions() {
        return selection.evictions();
    }

    public List<ContextItem> requiredOverflowItems() {
        return selection.requiredOverflowItems();
    }

    public long postCompressionUsage(ContextBudgetSection section) {
        Objects.requireNonNull(section, "section must not be null");
        return postCompressionUsage.getOrDefault(section, 0L);
    }

    private static Map<ContextBudgetSection, Long> snapshotUsage(Map<ContextBudgetSection, Long> usage) {
        Objects.requireNonNull(usage, "post compression usage must not be null");
        EnumMap<ContextBudgetSection, Long> snapshot = new EnumMap<>(ContextBudgetSection.class);
        usage.forEach((section, value) -> {
            Objects.requireNonNull(section, "usage section must not be null");
            Objects.requireNonNull(value, "usage value must not be null");
            if (value < 0) {
                throw new IllegalArgumentException("usage values must not be negative");
            }
            snapshot.put(section, value);
        });
        return Map.copyOf(snapshot);
    }

    public record Diagnostics(
            int candidateCount,
            int selectedCount,
            int evictedCount,
            int compressedCount,
            long originalSelectedCharacters,
            long compressedSelectedCharacters,
            long charactersSaved,
            boolean requiredOverflow) {
        public Diagnostics {
            if (candidateCount < 0 || selectedCount < 0 || evictedCount < 0 || compressedCount < 0) {
                throw new IllegalArgumentException("diagnostic counts must not be negative");
            }
            if (selectedCount > candidateCount || evictedCount > candidateCount || compressedCount > selectedCount) {
                throw new IllegalArgumentException("diagnostic counts are inconsistent");
            }
            if (originalSelectedCharacters < 0 || compressedSelectedCharacters < 0 || charactersSaved < 0) {
                throw new IllegalArgumentException("diagnostic character counts must not be negative");
            }
            if (compressedSelectedCharacters > originalSelectedCharacters
                    || charactersSaved != originalSelectedCharacters - compressedSelectedCharacters) {
                throw new IllegalArgumentException("diagnostic character counts are inconsistent");
            }
        }
    }
}
