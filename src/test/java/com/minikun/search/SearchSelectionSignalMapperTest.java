package com.minikun.search;

import com.minikun.pcs.SearchSelectionSignals;
import com.minikun.search.model.SearchDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SearchSelectionSignalMapperTest {
    private final SearchSelectionSignalMapper mapper = new SearchSelectionSignalMapper();

    @Test
    void mapsPositiveDecisionToPositiveSignal() {
        assertEquals(new SearchSelectionSignals(true),
                mapper.map(new SearchDecision(true, "latest Java")));
    }

    @Test
    void reusesCanonicalEmptyForNegativeDecision() {
        SearchSelectionSignals first = mapper.map(new SearchDecision(false, "explain Java"));
        SearchSelectionSignals second = mapper.map(new SearchDecision(false, "explain Java"));

        assertSame(SearchSelectionSignals.EMPTY, first);
        assertSame(first, second);
    }
}
