package com.minikun.pcs;

public record MemorySelectionSignals(
        boolean memoryAvailable,
        int recalledMemoryCount,
        boolean memoryRecallPerformed) {
    public static final MemorySelectionSignals EMPTY =
            new MemorySelectionSignals(false, 0, false);

    public MemorySelectionSignals {
        if (recalledMemoryCount < 0) {
            throw new IllegalArgumentException("recalledMemoryCount must not be negative");
        }
    }
}