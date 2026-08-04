package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SelectionContextFactoryTest {
    private final SelectionContextFactory factory = new SelectionContextFactory();

    @Test
    void createsContextWithCanonicalRuntimeDefaults() {
        McsSelectionContext context = factory.create("message", "history");

        assertEquals("message", context.currentUserMessage());
        assertEquals("history", context.conversationHistory());
        assertSame(ConversationAttributes.EMPTY, context.conversationAttributes());
        assertSame(RuntimeAttributes.EMPTY, context.runtimeAttributes());
        assertSame(MemorySelectionSignals.EMPTY, context.memorySelectionSignals());
        assertSame(InterestSelectionSignals.EMPTY, context.interestSelectionSignals());
    }

    @Test
    void normalizesNullInputs() {
        McsSelectionContext context = factory.create(null, null);

        assertEquals("", context.currentUserMessage());
        assertEquals("", context.conversationHistory());
    }

    @Test
    void repeatedCreationHasDeterministicValueSemantics() {
        McsSelectionContext first = factory.create("message", "history");
        McsSelectionContext second = factory.create("message", "history");

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first.toString(), second.toString());
    }

    @Test
    void delegatesExactlyOnceAndTransportsTheExactProducedSignal() {
        InterestSelectionSignals produced = new InterestSelectionSignals(true);
        AtomicInteger invocationCount = new AtomicInteger();
        AtomicReference<String> message = new AtomicReference<>();
        AtomicReference<String> history = new AtomicReference<>();
        InterestSelectionSignalProducer producer = (currentMessage, conversationHistory) -> {
            invocationCount.incrementAndGet();
            message.set(currentMessage);
            history.set(conversationHistory);
            return produced;
        };

        McsSelectionContext context = new SelectionContextFactory(producer)
                .create(null, null);

        assertEquals(1, invocationCount.get());
        assertEquals("", message.get());
        assertEquals("", history.get());
        assertSame(produced, context.interestSelectionSignals());
    }
}
