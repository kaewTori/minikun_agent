package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SearchSelectionSignalsTest {
    @Test
    void exposesCanonicalEmptyValue() {
        assertSame(SearchSelectionSignals.EMPTY, SearchSelectionSignals.EMPTY);
        assertEquals(new SearchSelectionSignals(false), SearchSelectionSignals.EMPTY);
    }

    @Test
    void hasDeterministicValueSemantics() {
        SearchSelectionSignals first = new SearchSelectionSignals(true);
        SearchSelectionSignals second = new SearchSelectionSignals(true);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals("SearchSelectionSignals[searchRequested=true]", first.toString());
    }
}
