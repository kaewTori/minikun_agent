package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class InterestSelectionSignalProducerTest {
    private final InterestSelectionSignalProducer producer =
            new NoOpInterestSelectionSignalProducer();

    @Test
    void producesTheCanonicalEmptySignalDeterministically() {
        InterestSelectionSignals first = producer.produce("message", "history");
        InterestSelectionSignals second = producer.produce("message", "history");

        assertSame(InterestSelectionSignals.EMPTY, first);
        assertSame(first, second);
    }

    @Test
    void remainsStatelessAcrossDifferentInputs() {
        assertSame(InterestSelectionSignals.EMPTY, producer.produce("first", "history"));
        assertSame(InterestSelectionSignals.EMPTY, producer.produce("second", "other history"));
        assertSame(InterestSelectionSignals.EMPTY, producer.produce(null, null));
    }

    @Test
    void preservesImmutableValueSemantics() {
        InterestSelectionSignals result = producer.produce("message", "history");

        assertEquals(new InterestSelectionSignals(false), result);
        assertEquals("InterestSelectionSignals[interestMatchAvailable=false]", result.toString());
    }
}
