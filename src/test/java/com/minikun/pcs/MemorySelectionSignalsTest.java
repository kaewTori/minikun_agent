package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySelectionSignalsTest {
    @Test
    void emptyIsCanonicalAndUsesDeterministicDefaults() {
        MemorySelectionSignals signals = MemorySelectionSignals.EMPTY;

        assertSame(MemorySelectionSignals.EMPTY, signals);
        assertFalse(signals.memoryAvailable());
        assertEquals(0, signals.recalledMemoryCount());
        assertFalse(signals.memoryRecallPerformed());
    }

    @Test
    void fullValueIsImmutableAndHasDeterministicValueSemantics() {
        MemorySelectionSignals first = new MemorySelectionSignals(true, 3, true);
        MemorySelectionSignals second = new MemorySelectionSignals(true, 3, true);

        assertTrue(first.memoryAvailable());
        assertEquals(3, first.recalledMemoryCount());
        assertTrue(first.memoryRecallPerformed());
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first.toString(), second.toString());
    }

    @Test
    void negativeRecalledMemoryCountIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new MemorySelectionSignals(false, -1, false));
    }
}