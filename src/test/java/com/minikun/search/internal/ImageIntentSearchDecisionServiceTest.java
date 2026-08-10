package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.search.SearchDecisionService;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImageIntentSearchDecisionServiceTest {
    private final ImageIntentDetector detector = new ImageIntentDetector();

    @Test
    void promotesOnlyNonSearchGeneralKnowledge() {
        SearchDecision original = new SearchDecision(false, "หารูปแมว", SearchDecisionReason.GENERAL_KNOWLEDGE);
        SearchDecision decision = serviceReturning(original).decide(original.query());

        assertTrue(decision.shouldSearch());
        assertEquals(SearchDecisionReason.IMAGE_REQUEST, decision.reason());
        assertEquals(original.query(), decision.query());
    }

    @Test
    void preservesStrongerExistingDecisionsWithImageWording() {
        List<SearchDecision> decisions = List.of(
                new SearchDecision(true, "หารูปข่าวล่าสุดของ Tesla", SearchDecisionReason.CURRENT_INFORMATION),
                new SearchDecision(true, "show me a photo from GitHub", SearchDecisionReason.EXTERNAL_RESOURCE),
                new SearchDecision(true, "pictures of Java", SearchDecisionReason.FACT_LOOKUP),
                new SearchDecision(false, "หารูปแมว", SearchDecisionReason.RULE_FALLBACK),
                new SearchDecision(true, "หารูปแมว", SearchDecisionReason.GENERAL_KNOWLEDGE));

        for (SearchDecision original : decisions) {
            SearchDecision decision = serviceReturning(original).decide(original.query());
            assertSame(original, decision, original.reason().name());
        }
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