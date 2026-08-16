package com.minikun.personality.signal;

import java.util.List;

/** Immutable runtime facts. It contains signals, never policy decisions. */
public record PersonaSelectionSignals(
        boolean memoryAvailable,
        int recalledMemoryCount,
        boolean profileAvailable,
        boolean preferenceAvailable,
        boolean toolInvocationRequested,
        boolean toolRequiresConfirmation,
        boolean searchRequested,
        boolean moodActive,
        String mood,
        List<String> preferenceKeys) {
    public static final PersonaSelectionSignals EMPTY = new PersonaSelectionSignals(
            false, 0, false, false, false, false, false, false, "CALM", List.of());

    public PersonaSelectionSignals {
        if (recalledMemoryCount < 0) throw new IllegalArgumentException("recalledMemoryCount must not be negative");
        mood = mood == null || mood.isBlank() ? "CALM" : mood;
        preferenceKeys = preferenceKeys == null ? List.of() : List.copyOf(preferenceKeys);
    }
}
