package com.minikun.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.conversation.ConversationId;

class MemoryScopeTest {
    @Test
    void isValueBasedAndImmutable() {
        MemoryScope first = new MemoryScope("owner-a", new ConversationId("conversation-a"));
        MemoryScope second = new MemoryScope("owner-a", new ConversationId("conversation-a"));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void rejectsMissingBlankAndWildcardOwners() {
        ConversationId conversation = new ConversationId("conversation-a");

        assertThrows(NullPointerException.class, () -> new MemoryScope(null, conversation));
        assertThrows(IllegalArgumentException.class, () -> new MemoryScope("", conversation));
        assertThrows(IllegalArgumentException.class, () -> new MemoryScope(" ", conversation));
        assertThrows(IllegalArgumentException.class, () -> new MemoryScope("*", conversation));
    }

    @Test
    void requiresConversationIdentity() {
        assertThrows(NullPointerException.class, () -> new MemoryScope("owner-a", null));
    }
}
