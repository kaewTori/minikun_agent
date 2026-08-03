package com.minikun.memory.reflection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.MemoryException;
import com.minikun.memory.model.MemoryCategory;

class ReflectionParserTest {
    private final ReflectionParser parser = new ReflectionParser(new ObjectMapper());

    @Test
    void parsesValidResponse() {
        var memories = parser.parse("""
                {"memories":[{"category":"PROFILE","content":"Uses macOS","confidence":0.9,"reason":"User stated it"}]}
                """);

        assertEquals(1, memories.size());
        assertEquals(MemoryCategory.PROFILE, memories.getFirst().category());
        assertEquals("Uses macOS", memories.getFirst().content());
    }

    @Test
    void rejectsMalformedMissingAdditionalInvalidAndNullFields() {
        assertThrows(MemoryException.class, () -> parser.parse("not-json"));
        assertThrows(MemoryException.class, () -> parser.parse("{\"memories\":[{\"category\":\"PROFILE\"}]}"));
        assertThrows(MemoryException.class, () -> parser.parse(
                "{\"memories\":[{\"category\":\"PROFILE\",\"content\":\"x\",\"confidence\":0.9,\"reason\":\"r\",\"extra\":true}]}"));
        assertThrows(MemoryException.class, () -> parser.parse(
                "{\"memories\":[{\"category\":\"UNKNOWN\",\"content\":\"x\",\"confidence\":0.9,\"reason\":\"r\"}]}"));
        assertThrows(MemoryException.class, () -> parser.parse(
                "{\"memories\":[{\"category\":null,\"content\":\"x\",\"confidence\":0.9,\"reason\":\"r\"}]}"));
    }
}