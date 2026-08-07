package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompositeInterestSelectionSignalProducerTest {
    @Test
    void invokesTheSingleDelegateExactlyOnceAndReturnsItsSignalByIdentity() {
        InterestSelectionSignals produced = new InterestSelectionSignals(true);
        AtomicInteger invocationCount = new AtomicInteger();
        AtomicReference<String> message = new AtomicReference<>();
        AtomicReference<String> history = new AtomicReference<>();
        InterestSelectionSignalProducer delegate = (currentMessage, conversationHistory) -> {
            invocationCount.incrementAndGet();
            message.set(currentMessage);
            history.set(conversationHistory);
            return produced;
        };

        InterestSelectionSignals result =
                new CompositeInterestSelectionSignalProducer(List.of(delegate))
                        .produce("message", "history");

        assertEquals(1, invocationCount.get());
        assertEquals("message", message.get());
        assertEquals("history", history.get());
        assertSame(produced, result);
    }

    @Test
    void preservesTheDelegateRegistrationDuringConstruction() {
        AtomicInteger invocationCount = new AtomicInteger();
        InterestSelectionSignalProducer delegate = (message, history) -> {
            invocationCount.incrementAndGet();
            return InterestSelectionSignals.EMPTY;
        };
        List<InterestSelectionSignalProducer> registered = new ArrayList<>(List.of(delegate));
        CompositeInterestSelectionSignalProducer composite =
                new CompositeInterestSelectionSignalProducer(registered);

        registered.clear();
        composite.produce("message", "history");

        assertEquals(1, invocationCount.get());
    }

    @Test
    void repeatedEvaluationIsDeterministic() {
        CompositeInterestSelectionSignalProducer composite =
                new CompositeInterestSelectionSignalProducer(
                        List.of(new NoOpInterestSelectionSignalProducer()));

        InterestSelectionSignals first = composite.produce("message", "history");
        InterestSelectionSignals second = composite.produce("message", "history");

        assertSame(InterestSelectionSignals.EMPTY, first);
        assertSame(first, second);
    }

    @Test
    void rejectsInvalidDelegateListsAtConstruction() {
        assertThrows(NullPointerException.class,
                () -> new CompositeInterestSelectionSignalProducer(null));
        assertThrows(IllegalArgumentException.class,
                () -> new CompositeInterestSelectionSignalProducer(List.of()));
        assertThrows(NullPointerException.class,
                () -> new CompositeInterestSelectionSignalProducer(List.of((InterestSelectionSignalProducer) null)));
        assertThrows(IllegalArgumentException.class,
                () -> new CompositeInterestSelectionSignalProducer(List.of(
                        new NoOpInterestSelectionSignalProducer(),
                        new NoOpInterestSelectionSignalProducer())));
    }
}
