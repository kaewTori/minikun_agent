package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.task.TaskModelProvider;
import com.minikun.search.SearchDecisionClientException;
import com.minikun.search.model.SearchDecisionPrompt;
import com.minikun.search.model.SearchDecisionReason;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TaskModelSearchDecisionProviderTest {
    private static final SearchDecisionPrompt PROMPT = new SearchDecisionPrompt(
            "Classify search intent", "2026-08-29", "ทดสอบ");

    @Test
    void normalizesFalseCurrentInformationToSearch() {
        TaskModelProvider model = request ->
                "{\"shouldSearch\":false,\"reason\":\"CURRENT_INFORMATION\"}";
        var provider = new TaskModelSearchDecisionProvider(
                model, new ObjectMapper(), Duration.ofSeconds(1));

        assertTrue(provider.classify(PROMPT).shouldSearch());
    }

    @Test
    void normalizesTrueGeneralKnowledgeToNoSearch() {
        TaskModelProvider model = request ->
                "{\"shouldSearch\":true,\"reason\":\"GENERAL_KNOWLEDGE\"}";
        var provider = new TaskModelSearchDecisionProvider(
                model, new ObjectMapper(), Duration.ofSeconds(1));

        assertFalse(provider.classify(PROMPT).shouldSearch());
    }

    @Test
    void acceptsSemanticImageIntent() {
        TaskModelProvider model = request ->
                """
                {"shouldSearch":true,"reason":"IMAGE_REQUEST","intent":"images",
                 "confidence":0.98,"searchQuery":"แมว","alternateQueries":[],"evidenceNeeds":[],"location":""}
                """;
        var provider = new TaskModelSearchDecisionProvider(
                model, new ObjectMapper(), Duration.ofSeconds(1));

        var decision = provider.classify(PROMPT);

        assertTrue(decision.shouldSearch());
        assertEquals(SearchDecisionReason.IMAGE_REQUEST, decision.reason());
        assertEquals("images", decision.planHints().intent());
        assertEquals("แมว", decision.planHints().primaryQuery());
    }

    @Test
    void rejectsInternalFallbackReasonFromTheModel() {
        TaskModelProvider model = request ->
                "{\"shouldSearch\":true,\"reason\":\"RULE_FALLBACK\"}";
        var provider = new TaskModelSearchDecisionProvider(
                model, new ObjectMapper(), Duration.ofSeconds(1));

        assertThrows(SearchDecisionClientException.class, () -> provider.classify(PROMPT));
    }

    @Test
    void parsesDecisionAndSemanticSearchPlanInOneResponse() {
        TaskModelProvider model = request -> """
                {"shouldSearch":true,"reason":"EXTERNAL_RESOURCE","intent":"local_discovery",
                 "confidence":0.96,"searchQuery":"ร้านข้าว บางขุนนนท์ MRT ไฟฉาย",
                 "alternateQueries":["ร้านอาหารใกล้ MRT ไฟฉาย รีวิว เวลาเปิด"],
                 "evidenceNeeds":["opening_hours","rating","location","price"],
                 "location":"บางขุนนนท์ MRT ไฟฉาย"}
                """;
        var provider = new TaskModelSearchDecisionProvider(
                model, new ObjectMapper(), Duration.ofSeconds(1));

        var decision = provider.classify(PROMPT);

        assertTrue(decision.shouldSearch());
        assertEquals("local_discovery", decision.planHints().intent());
        assertEquals("ร้านข้าว บางขุนนนท์ MRT ไฟฉาย", decision.planHints().primaryQuery());
        assertEquals(4, decision.planHints().evidenceNeeds().size());
        assertEquals(0.96, decision.planHints().confidence());
    }
}
