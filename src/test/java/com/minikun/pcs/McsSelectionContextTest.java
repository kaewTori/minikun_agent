package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McsSelectionContextTest {
    @Test
    void compatibilityConstructorUsesDeterministicDefaults() {
        McsSelectionContext context = new McsSelectionContext("message", "history");

        assertEquals("message", context.currentUserMessage());
        assertEquals("history", context.conversationHistory());
        assertSame(ConversationAttributes.EMPTY, context.conversationAttributes());
        assertSame(RuntimeAttributes.EMPTY, context.runtimeAttributes());
        assertSame(MemorySelectionSignals.EMPTY, context.memorySelectionSignals());
        assertSame(InterestSelectionSignals.EMPTY, context.interestSelectionSignals());
        }

        @Test
        void fourArgumentCompatibilityConstructorUsesTheFullConstructorDefaults() {
        ConversationAttributes conversation = new ConversationAttributes("conversation-1", 3, true);
        RuntimeAttributes runtime = new RuntimeAttributes(true, true, ResponseMode.DEFAULT);

        McsSelectionContext compatibility = new McsSelectionContext(
            "message", "history", conversation, runtime);
        McsSelectionContext full = new McsSelectionContext(
            "message", "history", conversation, runtime, MemorySelectionSignals.EMPTY,
            InterestSelectionSignals.EMPTY);

        assertEquals(full, compatibility);
        assertSame(MemorySelectionSignals.EMPTY, compatibility.memorySelectionSignals());
        assertSame(InterestSelectionSignals.EMPTY, compatibility.interestSelectionSignals());
    }

    @Test
    void fullConstructorRetainsAllRuntimeObservations() {
        ConversationAttributes conversation = new ConversationAttributes("conversation-1", 3, true);
        RuntimeAttributes runtime = new RuntimeAttributes(true, true, ResponseMode.DEFAULT);
        MemorySelectionSignals memory = new MemorySelectionSignals(true, 2, true);
        InterestSelectionSignals interests = new InterestSelectionSignals(true);

        McsSelectionContext context = new McsSelectionContext(
            "message", "history", conversation, runtime, memory, interests);

        assertEquals(conversation, context.conversationAttributes());
        assertEquals(runtime, context.runtimeAttributes());
        assertEquals(memory, context.memorySelectionSignals());
        assertEquals(interests, context.interestSelectionSignals());
    }

    @Test
    void nullValuesUseEmptyContextDefaults() {
        McsSelectionContext context = new McsSelectionContext(null, null, null, null, null, null);

        assertEquals("", context.currentUserMessage());
        assertEquals("", context.conversationHistory());
        assertEquals(ConversationAttributes.EMPTY, context.conversationAttributes());
        assertEquals(RuntimeAttributes.EMPTY, context.runtimeAttributes());
        assertSame(MemorySelectionSignals.EMPTY, context.memorySelectionSignals());
        assertSame(InterestSelectionSignals.EMPTY, context.interestSelectionSignals());
    }

    @Test
    void equalContextsRemainEqualAndDeterministic() {
        McsSelectionContext first = new McsSelectionContext(
                "message", "history",
                new ConversationAttributes("conversation-1", 2, false),
                new RuntimeAttributes(true, false, ResponseMode.DEFAULT));
        McsSelectionContext second = new McsSelectionContext(
                "message", "history",
                new ConversationAttributes("conversation-1", 2, false),
                new RuntimeAttributes(true, false, ResponseMode.DEFAULT));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first.toString(), second.toString());
    }

    @Test
    void conversationAttributesAreImmutableAndValidateMessageCount() {
        ConversationAttributes attributes = new ConversationAttributes(null, 4, true);

        assertEquals("", attributes.conversationId());
        assertEquals(4, attributes.messageCount());
        assertTrue(attributes.firstMessage());
        assertThrows(IllegalArgumentException.class, () -> new ConversationAttributes("id", -1, false));
    }

    @Test
    void runtimeAttributesAreImmutableAndDefaultNullResponseMode() {
        RuntimeAttributes attributes = new RuntimeAttributes(true, false, null);

        assertTrue(attributes.searchRequested());
        assertFalse(attributes.toolInvocationRequested());
        assertEquals(ResponseMode.DEFAULT, attributes.responseMode());
        assertEquals(ResponseMode.DEFAULT, RuntimeAttributes.EMPTY.responseMode());
    }
}
