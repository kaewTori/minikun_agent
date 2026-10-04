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
    void typhoonClassifiesIndependentRequestsAndFocusesRecommendation() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        TaskModelProvider classifier = request -> {
            calls.incrementAndGet();
            String message = request.messages().getLast().content();
            return message.contains("น่ากิน")
                    ? """
                      {"reason":"EXTERNAL_RESOURCE","searchQuery":"ร้านอาหารใกล้ฉัน"}
                      """
                    : """
                      {"reason":"CURRENT_INFORMATION","searchQuery":"สภาพอากาศตอนนี้"}
                      """;
        };
        var provider = new TaskModelSearchDecisionProvider(
                classifier, new ObjectMapper(), Duration.ofSeconds(1));

        var decision = provider.classify(new SearchDecisionPrompt(
                "Classify search intent", "2026-09-29",
                "ขอสภาพอากาศตอนนี้ แล้วมีอะไรน่ากินมั้ง แถวนี้"));

        assertEquals(SearchDecisionReason.EXTERNAL_RESOURCE, decision.reason());
        assertEquals("local_discovery", decision.planHints().intent());
        assertEquals("ร้านอาหารใกล้ฉัน", decision.planHints().primaryQuery());
        assertEquals(java.util.List.of("สภาพอากาศตอนนี้"), decision.planHints().alternateQueries());
        assertEquals(2, calls.get());
    }

    @Test
    void mixedRequestKeepsFocusedClauseWhenModelOmitsSearchQuery() {
        TaskModelProvider classifier = request -> request.messages().getLast().content().contains("น่ากิน")
                ? "{\"reason\":\"EXTERNAL_RESOURCE\",\"searchQuery\":\"\"}"
                : "{\"reason\":\"CURRENT_INFORMATION\",\"searchQuery\":\"สภาพอากาศ\"}";
        var provider = new TaskModelSearchDecisionProvider(
                classifier, new ObjectMapper(), Duration.ofSeconds(1));

        var decision = provider.classify(new SearchDecisionPrompt(
                "Classify search intent", "2026-09-29", "ขอสภาพอากาศ แล้วมีอะไรน่ากินแถวนี้"));

        assertEquals("มีอะไรน่ากินแถวนี้", decision.planHints().primaryQuery());
        assertEquals(java.util.List.of("สภาพอากาศ"), decision.planHints().alternateQueries());
    }

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

    @Test
    void acceptsAtmosphereEvidenceNeedForPlaceDiscovery() {
        TaskModelProvider model = request -> """
                {"shouldSearch":true,"reason":"EXTERNAL_RESOURCE","intent":"local_discovery",
                 "confidence":0.94,"searchQuery":"พิพิธภัณฑ์ เชียงใหม่ เงียบ",
                 "alternateQueries":[],"evidenceNeeds":["opening_hours","location","atmosphere"],
                 "location":"เชียงใหม่"}
                """;
        var provider = new TaskModelSearchDecisionProvider(
                model, new ObjectMapper(), Duration.ofSeconds(1));

        var decision = provider.classify(PROMPT);

        assertTrue(decision.planHints().evidenceNeeds().contains("atmosphere"));
    }
}
