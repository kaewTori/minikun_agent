package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SearchInterestSelectionSignalProducerTest {
    private final SearchInterestSelectionSignalProducer producer =
            new SearchInterestSelectionSignalProducer();

    @Test
    void projectsPositiveObservationDeterministically() {
        SearchSelectionSignals signals = new SearchSelectionSignals(true);

        InterestSelectionSignals first = producer.produce(signals);
        InterestSelectionSignals second = producer.produce(signals);

        assertEquals(new InterestSelectionSignals(true), first);
        assertEquals(first, second);
    }

    @Test
    void reusesCanonicalEmptyForNegativeObservation() {
        assertSame(InterestSelectionSignals.EMPTY, producer.produce(SearchSelectionSignals.EMPTY));
        assertSame(InterestSelectionSignals.EMPTY,
                producer.produce(new SearchSelectionSignals(false)));
    }

    @Test
    void remainsStatelessAcrossObservations() {
        assertEquals(new InterestSelectionSignals(true),
                producer.produce(new SearchSelectionSignals(true)));
        assertSame(InterestSelectionSignals.EMPTY,
                producer.produce(SearchSelectionSignals.EMPTY));
    }
}
