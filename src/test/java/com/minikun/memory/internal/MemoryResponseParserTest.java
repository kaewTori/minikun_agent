package com.minikun.memory.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.MemoryException;

import org.junit.jupiter.api.Test;

class MemoryResponseParserTest {
    private final MemoryResponseParser parser = new MemoryResponseParser(new ObjectMapper());

    @Test
    void parsesStructuredMemoryArray() {
        var result = parser.parse("""
                {"memories":[{"category":"GOAL","content":"Ship Memory v2","confidence":0.9,"reason":"explicit goal"}]}
                """);

        assertEquals("GOAL", result.getFirst().category());
        assertEquals(0.9, result.getFirst().confidence());
    }

    @Test
    void parsesConversationWithNoMemories() {
        assertEquals(0, parser.parse("{\"memories\":[]}").size());
    }

    @Test
    void rejectsMalformedAndEmptyResponses() {
        assertThrows(MemoryException.class, () -> parser.parse("not json"));
        assertThrows(MemoryException.class, () -> parser.parse("{}"));
        assertThrows(MemoryException.class, () -> parser.parse("  "));
    }
}
