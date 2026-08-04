package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterestSelectionSignalsTest {
    @Test
    void emptyIsCanonicalAndDisabled() {
        assertSame(InterestSelectionSignals.EMPTY, InterestSelectionSignals.EMPTY);
        assertEquals(InterestSelectionSignals.EMPTY, new InterestSelectionSignals(false));
        assertFalse(InterestSelectionSignals.EMPTY.interestMatchAvailable());
    }

    @Test
    void valueSemanticsAreDeterministic() {
        InterestSelectionSignals first = new InterestSelectionSignals(true);
        InterestSelectionSignals second = new InterestSelectionSignals(true);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first.toString(), second.toString());
    }

    @Test
    void enabledSignalIsImmutableValue() {
        InterestSelectionSignals signals = new InterestSelectionSignals(true);

        assertTrue(signals.interestMatchAvailable());
        assertEquals("InterestSelectionSignals[interestMatchAvailable=true]", signals.toString());
    }
}
