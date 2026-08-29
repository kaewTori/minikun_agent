package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SearchDecisionPromptBuilderTest {
    @Test
    void buildsDeterministicSemanticPrompt() {
        SearchDecisionPromptBuilder builder = new SearchDecisionPromptBuilder();

        var first = builder.build(LocalDate.of(2026, 8, 2), "today's gold price");
        var second = builder.build(LocalDate.of(2026, 8, 2), "today's gold price");

        assertEquals(first, second);
        assertEquals("2026-08-02", first.currentDate());
        assertEquals("today's gold price", first.userMessage());
        assertTrue(first.instructions().contains("external web search"));
        assertTrue(first.instructions().contains("Do not output thinking"));
        assertTrue(first.instructions().contains("{\"shouldSearch\":true"));
        assertTrue(first.instructions().contains(
            "CURRENT_INFORMATION, FACT_LOOKUP, EXTERNAL_RESOURCE, GENERAL_KNOWLEDGE"));
        assertTrue(first.instructions().contains(
            "GENERAL_KNOWLEDGE always requires shouldSearch=false"));
        assertTrue(first.instructions().contains("แล้วตอนนี้ล่ะ"));
        assertTrue(first.instructions().contains("Never output RULE_FALLBACK"));
        assertFalse(first.instructions().contains("system prompt"));
        assertFalse(first.instructions().contains("chat messages"));
    }
}
