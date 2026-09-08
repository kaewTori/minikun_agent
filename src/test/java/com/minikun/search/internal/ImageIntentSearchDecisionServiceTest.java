package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.search.SearchDecisionService;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import com.minikun.search.model.SearchPlanHints;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImageIntentSearchDecisionServiceTest {
    private final ImageIntentDetector detector = new ImageIntentDetector();

    @Test
    void promotesNonSearchGeneralKnowledge() {
        SearchDecision original = new SearchDecision(false, "หารูปแมว", SearchDecisionReason.GENERAL_KNOWLEDGE);
        SearchDecision decision = serviceReturning(original).decide(original.query());

        assertTrue(decision.shouldSearch());
        assertEquals(SearchDecisionReason.IMAGE_REQUEST, decision.reason());
        assertEquals(original.query(), decision.query());
    }

    @Test
    void explicitImageIntentOverridesGenericSearchClassification() {
        List<SearchDecision> decisions = List.of(
                new SearchDecision(true, "หารูปข่าวล่าสุดของ Tesla", SearchDecisionReason.CURRENT_INFORMATION),
                new SearchDecision(true, "show me a photo from GitHub", SearchDecisionReason.EXTERNAL_RESOURCE),
                new SearchDecision(true, "pictures of Java", SearchDecisionReason.FACT_LOOKUP),
                new SearchDecision(false, "หารูปแมว", SearchDecisionReason.RULE_FALLBACK),
                new SearchDecision(true, "หารูปแมว", SearchDecisionReason.GENERAL_KNOWLEDGE),
                new SearchDecision(false, "อยากได้รูปแมว", SearchDecisionReason.GENERAL_KNOWLEDGE));

        for (SearchDecision original : decisions) {
            SearchDecision decision = serviceReturning(original).decide(original.query());
            assertTrue(decision.shouldSearch(), original.reason().name());
            assertEquals(SearchDecisionReason.IMAGE_REQUEST, decision.reason(), original.reason().name());
            assertEquals(original.query(), decision.query(), original.reason().name());
        }
    }

    @Test
    void detectsConversationalThaiImageRecommendation() {
        SearchDecision original = new SearchDecision(
                true,
                "มีรูปผลงานที่น่าสนใจอยากแนะนำไหม",
                SearchDecisionReason.CURRENT_INFORMATION);

        SearchDecision decision = serviceReturning(original).decide(original.query());

        assertEquals(SearchDecisionReason.IMAGE_REQUEST, decision.reason());
    }

    @Test
    void preservesVisualArtistLookupAsTextSearch() {
        String query = "ช่วยค้นหาข้อมูลของนักวาดที่ชื่อ RenaRaziel หน่อย";
        SearchDecision original = new SearchDecision(
                true, query, SearchDecisionReason.FACT_LOOKUP);

        SearchDecision decision = serviceReturning(original).decide(query);

        assertSame(original, decision);
    }

    @Test
    void detectsArtworkRecommendationFromVisualArtistContext() {
        SearchDecision original = new SearchDecision(
                false,
                "มีผลงานที่น่าสนใจอยากแนะนำไหม",
                SearchDecisionReason.RULE_FALLBACK);

        SearchDecision decision = serviceReturning(original).decide(
                original.query(),
                "user: RenaRaziel คือใคร\nassistant: เธอเป็นนักวาดภาพประกอบและมีผลงานบน Pixiv");

        assertTrue(decision.shouldSearch());
        assertEquals(SearchDecisionReason.IMAGE_REQUEST, decision.reason());
    }

    @Test
    void doesNotAssumeImagesForArtworkOutsideVisualArtistContext() {
        SearchDecision original = new SearchDecision(
                false,
                "มีผลงานที่น่าสนใจอยากแนะนำไหม",
                SearchDecisionReason.RULE_FALLBACK);

        SearchDecision decision = serviceReturning(original).decide(
                original.query(),
                "user: นักเขียนคนนี้คือใคร\nassistant: เธอเขียนนวนิยายหลายเล่ม");

        assertSame(original, decision);
    }

    @Test
    void preservesAnExistingImageDecision() {
        SearchDecision original = new SearchDecision(true, "หารูปแมว", SearchDecisionReason.IMAGE_REQUEST);

        assertSame(original, serviceReturning(original).decide(original.query()));
    }

    @Test
    void preservesSemanticImageDecisionWithoutARecognizedPhrase() {
        SearchDecision original = new SearchDecision(
                true,
                "I would love a visual reference for a tortoise",
                SearchDecisionReason.IMAGE_REQUEST,
                new SearchPlanHints("images", 0.98, "tortoise", List.of(), List.of(), ""));

        assertSame(original, serviceReturning(original).decide(original.query()));
    }

    @Test
    void preservesGeneralKnowledgeWhenImageIntentIsAmbiguous() {
        List<String> queries = List.of(
                "รูปแบบการทำงาน",
                "ภาพรวมระบบ",
                "architecture explanation");

        for (String query : queries) {
            SearchDecision original = new SearchDecision(false, query, SearchDecisionReason.GENERAL_KNOWLEDGE);
            assertSame(original, serviceReturning(original).decide(query), query);
        }
    }

    private SearchDecisionService serviceReturning(SearchDecision decision) {
        return new ImageIntentSearchDecisionService(query -> decision, detector);
    }
}
