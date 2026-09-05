package com.minikun.memory.reflection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.MemoryException;
import com.minikun.memory.model.CompletedConversation;
import com.minikun.memory.model.MemoryCategory;

class ReflectionParserTest {
    private final ReflectionParser parser = new ReflectionParser(new ObjectMapper());
    private static final CompletedConversation CONVERSATION = new CompletedConversation(
            "conversation-1", List.of(new CompletedConversation.Message("user", "I use macOS")));

    @Test
    void parsesValidResponse() {
        var memories = parser.parse("""
                [{"category":"PROFILE","content":"Uses macOS","confidence":0.9,"reason":"User stated it"}]
                """, CONVERSATION);

        assertEquals(1, memories.size());
        assertEquals(MemoryCategory.PROFILE, memories.getFirst().category());
        assertEquals("Uses macOS", memories.getFirst().content());
        assertEquals("conversation-1", memories.getFirst().conversationId());
    }

    @Test
    void parsesJsonCodeFenceReturnedByChatModels() {
        var memories = parser.parse("""
                ```json
                [{"category":"PROFILE","content":"Uses macOS","confidence":0.9,"reason":"User stated it"}]
                ```
                """, CONVERSATION);

        assertEquals(1, memories.size());
        assertEquals("Uses macOS", memories.getFirst().content());
    }

    @Test
    void rejectsCodeFenceWithTrailingProse() {
        assertThrows(MemoryException.class, () -> parser.parse("""
                ```json
                []
                ```
                Here is the result.
                """, CONVERSATION));
    }

        @Test
    void parsesObjectResponseFromJsonObjectMode() {
                var memories = parser.parse("""
                                {"memories":[{"category":"PROFILE","content":"Uses macOS","confidence":0.9,"reason":"User stated it"}]}
                                """, CONVERSATION);

                assertEquals(1, memories.size());
                assertEquals(MemoryCategory.PROFILE, memories.getFirst().category());
        }

    @Test
    void parsesCompanionEpisodeMemory() {
        var memories = parser.parse("""
                {"memories":[{"category":"EPISODE","content":"ผู้ใช้ดีใจที่สอบผ่านใบรับรอง Java","confidence":0.95,"reason":"ผู้ใช้บอกเหตุการณ์และความหมายโดยตรง"}]}
                """, CONVERSATION);

        assertEquals(MemoryCategory.EPISODE, memories.getFirst().category());
    }

    @Test
    void preservesCardinalityAndOrder() {
        var memories = parser.parse("""
                [
                  {"category":"PROFILE","content":"first","confidence":0.1,"reason":"one"},
                  {"category":"GOAL","content":"second","confidence":0.2,"reason":"two"}
                ]
                """, CONVERSATION);

        assertEquals(2, memories.size());
        assertEquals("first", memories.get(0).content());
        assertEquals("second", memories.get(1).content());
        assertEquals(MemoryCategory.PROFILE, memories.get(0).category());
        assertEquals(MemoryCategory.GOAL, memories.get(1).category());
    }

    @Test
    void returnsImmutableEmptyListForEmptyArray() {
        var memories = parser.parse("[]", CONVERSATION);

        assertEquals(List.of(), memories);
        assertThrows(UnsupportedOperationException.class, () -> memories.add(null));
    }

    @Test
    void createsFreshCandidatesForEachInvocation() {
        String response = "[{\"category\":\"PROFILE\",\"content\":\"x\",\"confidence\":0.9,\"reason\":\"r\"}]";

        var first = parser.parse(response, CONVERSATION);
        var second = parser.parse(response, CONVERSATION);

        assertEquals(first, second);
        assertNotSame(first.getFirst(), second.getFirst());
    }

    @Test
    void rejectsMalformedMissingAdditionalInvalidAndNullFields() {
        assertThrows(MemoryException.class, () -> parser.parse("not-json", CONVERSATION));
        assertThrows(MemoryException.class, () -> parser.parse("[{\"category\":\"PROFILE\"}]", CONVERSATION));
        assertThrows(MemoryException.class, () -> parser.parse(
                "[{\"category\":\"PROFILE\",\"content\":\"x\",\"confidence\":0.9,\"reason\":\"r\",\"extra\":true}]", CONVERSATION));
        assertThrows(MemoryException.class, () -> parser.parse(
                "[{\"category\":\"UNKNOWN\",\"content\":\"x\",\"confidence\":0.9,\"reason\":\"r\"}]", CONVERSATION));
        assertThrows(MemoryException.class, () -> parser.parse(
                "[{\"category\":null,\"content\":\"x\",\"confidence\":0.9,\"reason\":\"r\"}]", CONVERSATION));
    }

    @Test
        void rejectsWrongTypesDuplicateFieldsInvalidConfidenceAndInvalidRoots() {
        assertThrows(MemoryException.class, () -> parser.parse(
                "[{\"category\":\"PROFILE\",\"content\":\"x\",\"confidence\":\"0.9\",\"reason\":\"r\"}]", CONVERSATION));
        assertThrows(MemoryException.class, () -> parser.parse(
                "[{\"category\":\"PROFILE\",\"category\":\"GOAL\",\"content\":\"x\",\"confidence\":0.9,\"reason\":\"r\"}]", CONVERSATION));
        assertThrows(MemoryException.class, () -> parser.parse(
                "[{\"category\":\"PROFILE\",\"content\":\"x\",\"confidence\":NaN,\"reason\":\"r\"}]", CONVERSATION));
        assertThrows(MemoryException.class, () -> parser.parse(
                "[{\"category\":\"PROFILE\",\"content\":\"x\",\"confidence\":Infinity,\"reason\":\"r\"}]", CONVERSATION));
        assertThrows(MemoryException.class, () -> parser.parse(
                "[{\"category\":\"PROFILE\",\"content\":\"x\",\"confidence\":-0.1,\"reason\":\"r\"}]", CONVERSATION));
        assertThrows(MemoryException.class, () -> parser.parse(
                "[{\"category\":\"PROFILE\",\"content\":\"x\",\"confidence\":1.1,\"reason\":\"r\"}]", CONVERSATION));
        assertThrows(MemoryException.class, () -> parser.parse("{\"other\":[]}", CONVERSATION));
        assertThrows(MemoryException.class, () -> parser.parse("42", CONVERSATION));
    }

    @Test
    void rejectsEntireResponseWhenAnyElementIsInvalid() {
        assertThrows(MemoryException.class, () -> parser.parse("""
                [
                  {"category":"PROFILE","content":"valid","confidence":0.9,"reason":"r"},
                  {"category":"UNKNOWN","content":"invalid","confidence":0.9,"reason":"r"}
                ]
                """, CONVERSATION));
    }
}
