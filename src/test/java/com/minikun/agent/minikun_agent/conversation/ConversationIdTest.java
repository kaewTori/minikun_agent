package com.minikun.agent.minikun_agent.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ConversationIdTest {

    @Test
    void acceptsNonBlankValues() {
        assertEquals("conversation-1", new ConversationId("conversation-1").value());
    }

    @Test
    void rejectsNullAndBlankValues() {
        assertThrows(NullPointerException.class, () -> new ConversationId(null));
        assertThrows(IllegalArgumentException.class, () -> new ConversationId(" "));
    }
}
