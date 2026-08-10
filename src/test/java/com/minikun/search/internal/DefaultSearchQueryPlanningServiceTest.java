package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import com.minikun.search.model.SearchQueryPlan;
import org.junit.jupiter.api.Test;

class DefaultSearchQueryPlanningServiceTest {
    private final DefaultSearchQueryPlanningService planner = new DefaultSearchQueryPlanningService();

    @Test
    void extractsThaiCoreQueryAndCurrentTimeRange() {
        SearchQueryPlan plan = planner.plan(
                "ช่วยค้นหาร้านกาแฟเปิดวันนี้แถวเชียงใหม่หน่อย",
                new SearchDecision(true, "ช่วยค้นหาร้านกาแฟเปิดวันนี้แถวเชียงใหม่หน่อย",
                        SearchDecisionReason.CURRENT_INFORMATION));

        assertEquals("ร้านกาแฟ เปิดวันนี้ แถว เชียงใหม่", plan.primaryQuery());
        assertEquals("th", plan.language());
        assertEquals("day", plan.timeRange());
        assertEquals("current_information", plan.intent());
        assertTrue(plan.coreTerms().contains("ร้านกาแฟ"));
        assertTrue(plan.coreTerms().contains("เชียงใหม่"));
    }

    @Test
    void removesEnglishSearchWrapperWithoutChangingEntity() {
        SearchQueryPlan plan = planner.plan(
                "Please search for Java 25 records \"pattern matching\"",
                new SearchDecision(true, "Please search for Java 25 records \"pattern matching\"",
                        SearchDecisionReason.CURRENT_INFORMATION));

        assertEquals("Java 25 records \"pattern matching\"", plan.primaryQuery());
        assertTrue(plan.primaryQuery().contains("Java 25"));
        assertTrue(plan.primaryQuery().contains("pattern matching"));
    }

    @Test
    void usesAllLanguageForMixedThaiAndEnglishQuery() {
        SearchQueryPlan plan = planner.plan(
                "ค้นหา Spring Boot latest version",
                new SearchDecision(true, "ค้นหา Spring Boot latest version",
                        SearchDecisionReason.CURRENT_INFORMATION));

        assertEquals("all", plan.language());
    }

    @Test
    void failsClosedWhenDecisionDoesNotRequestSearch() {
        SearchQueryPlan plan = planner.plan("ช่วยค้นหาข่าว", new SearchDecision(false, "ช่วยค้นหาข่าว"));

        assertFalse(plan.shouldSearch());
        assertEquals("", plan.primaryQuery());
        assertEquals("search_not_requested", plan.reason());
    }

    @Test
    void emitsImageIntentOnlyWhenDecisionAlreadyResolvedToImageRequest() {
        SearchQueryPlan imagePlan = planner.plan(
                "หารูปแมว",
                new SearchDecision(true, "หารูปแมว", SearchDecisionReason.IMAGE_REQUEST));
        SearchQueryPlan unrelatedPlan = planner.plan(
                "หารูปแมว",
                new SearchDecision(true, "หารูปแมว", SearchDecisionReason.FACT_LOOKUP));

        assertEquals("images", imagePlan.intent());
        assertEquals("fact_lookup", unrelatedPlan.intent());
    }
}
