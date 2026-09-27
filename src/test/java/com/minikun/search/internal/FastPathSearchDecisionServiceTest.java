package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.search.SearchDecisionService;
import com.minikun.search.model.SearchDecisionReason;
import org.junit.jupiter.api.Test;

class FastPathSearchDecisionServiceTest {
    @Test
    void sourceWordingCannotTakeGeneralTranslationFastPath() {
        SearchDecisionService delegate = query -> {
            throw new AssertionError("model should not be called");
        };
        SearchDecisionService service = new FastPathSearchDecisionService(
                delegate, new RuleBasedSearchDecisionService(null));

        assertTrue(service.decide("แปลเนื้อร้องเพลงนี้ให้หน่อย").shouldSearch());
        assertTrue(service.decide("แปลแบบเน้นความหมายลึกซึ้ง",
                "user: ช่วยแปลเพลงนั้นให้ฟังหน่อย").shouldSearch());
        assertFalse(service.decide("แปลคำว่า resilience").shouldSearch());
    }

    @Test
    void bypassesModelForObviousGeneralQuestion() {
        SearchDecisionService delegate = query -> {
            throw new AssertionError("model should not be called");
        };
        SearchDecisionService service = new FastPathSearchDecisionService(
                delegate, new RuleBasedSearchDecisionService(null));

        var decision = service.decide("อธิบาย dependency injection");

        assertEquals(SearchDecisionReason.GENERAL_KNOWLEDGE, decision.reason());
    }

    @Test
    void bypassesModelForObviousFreshnessQuestion() {
        SearchDecisionService delegate = query -> {
            throw new AssertionError("model should not be called");
        };
        SearchDecisionService service = new FastPathSearchDecisionService(
                delegate, new RuleBasedSearchDecisionService(null));

        var decision = service.decide("ข่าววันนี้เป็นอย่างไร");

        assertEquals(SearchDecisionReason.CURRENT_INFORMATION, decision.reason());
    }

    @Test
    void bypassesSearchForCasualConversationContainingToday() {
        SearchDecisionService delegate = query -> {
            throw new AssertionError("model should not be called");
        };
        SearchDecisionService service = new FastPathSearchDecisionService(
                delegate, new RuleBasedSearchDecisionService(null));

        var decision = service.decide("วันนี้เหนื่อยจัง ขอคุยเป็นเพื่อนหน่อย");

        assertFalse(decision.shouldSearch());
        assertEquals(SearchDecisionReason.GENERAL_KNOWLEDGE, decision.reason());
    }

    @Test
    void keepsLiveInformationOutOfCasualFastPath() {
        SearchDecisionService service = new FastPathSearchDecisionService(
                query -> new com.minikun.search.model.SearchDecision(false, query),
                new RuleBasedSearchDecisionService(null));

        var decision = service.decide("วันนี้อากาศเป็นไงบ้าง");

        assertTrue(decision.shouldSearch());
        assertEquals(SearchDecisionReason.CURRENT_INFORMATION, decision.reason());
    }

    @Test
    void explicitResearchUsesSearchFastPath() {
        SearchDecisionService delegate = query -> new com.minikun.search.model.SearchDecision(false, query);
        SearchDecisionService service = new FastPathSearchDecisionService(
                delegate, new RuleBasedSearchDecisionService(null));

        var decision = service.decide("ช่วยค้นคว้าเรื่องแบตเตอรี่แบบเจาะลึก");

        assertTrue(decision.shouldSearch());
        assertEquals(SearchDecisionReason.CURRENT_INFORMATION, decision.reason());
    }

    @Test
    void localRestaurantRecommendationAlwaysSearches() {
        java.util.concurrent.atomic.AtomicBoolean delegated = new java.util.concurrent.atomic.AtomicBoolean();
        SearchDecisionService service = new FastPathSearchDecisionService(
                query -> {
                    delegated.set(true);
                    return new com.minikun.search.model.SearchDecision(false, query);
                },
                new RuleBasedSearchDecisionService(null));

        var decision = service.decide(
                "ช่วยแนะนำร้านข้าวย่านบางขุนนนท์ ที่เราจะไปลงตรง MRT สถานีไฟฉายหน่อยสิ");

        assertTrue(decision.shouldSearch());
        assertTrue(delegated.get());
        assertEquals(SearchDecisionReason.EXTERNAL_RESOURCE, decision.reason());
    }

    @Test
    void localMuseumRecommendationAlwaysSearches() {
        java.util.concurrent.atomic.AtomicBoolean delegated = new java.util.concurrent.atomic.AtomicBoolean();
        SearchDecisionService service = new FastPathSearchDecisionService(
                query -> {
                    delegated.set(true);
                    return new com.minikun.search.model.SearchDecision(false, query);
                },
                new RuleBasedSearchDecisionService(null));

        var decision = service.decide("สนใจพิพิธภัณฑ์เงียบ ๆ แถวเชียงใหม่");

        assertTrue(decision.shouldSearch());
        assertTrue(delegated.get());
        assertEquals(SearchDecisionReason.EXTERNAL_RESOURCE, decision.reason());
    }

    @Test
    void sendsHighRiskConversationToTheConfiguredDecisionProvider() {
        java.util.concurrent.atomic.AtomicBoolean delegated = new java.util.concurrent.atomic.AtomicBoolean();
        SearchDecisionService service = new FastPathSearchDecisionService(
                query -> {
                    delegated.set(true);
                    return new com.minikun.search.model.SearchDecision(false, query);
                },
                new RuleBasedSearchDecisionService(null));

        service.decide("ช่วงนี้เครียดเรื่องภาษี อยากคุยด้วยหน่อย");

        assertTrue(delegated.get());
    }

    @Test
    void bypassesModelForCreativeThaiRequest() {
        SearchDecisionService delegate = query -> {
            throw new AssertionError("model should not be called");
        };
        SearchDecisionService service = new FastPathSearchDecisionService(
                delegate, new RuleBasedSearchDecisionService(null));

        var decision = service.decide("ช่วยแต่งคำอวยพรวันเกิดน่ารัก ๆ");

        assertFalse(decision.shouldSearch());
        assertEquals(SearchDecisionReason.GENERAL_KNOWLEDGE, decision.reason());
    }

    @Test
    void bypassesModelForThaiStoryRequest() {
        SearchDecisionService delegate = query -> {
            throw new AssertionError("model should not be called");
        };
        SearchDecisionService service = new FastPathSearchDecisionService(
                delegate, new RuleBasedSearchDecisionService(null));

        var decision = service.decide("มินิคุง เล่าเรื่องแฟนตาซีให้ฟังหน่อย");

        assertFalse(decision.shouldSearch());
    }

    @Test
    void keepsCreativeFollowUpOutOfSearch() {
        SearchDecisionService delegate = query -> {
            throw new AssertionError("model should not be called");
        };
        SearchDecisionService service = new FastPathSearchDecisionService(
                delegate, new RuleBasedSearchDecisionService(null));

        var decision = service.decide(
                "มันสั้นไปหน่อย แบ่งเป็นคำตอบละบทแทน",
                "user: มินิคุง เล่าเรื่องแฟนตาซีให้ฟังหน่อย\nassistant: กาลครั้งหนึ่ง...");

        assertFalse(decision.shouldSearch());
    }

    @Test
    void appliesRulesToAResolvedThaiFollowUpBeforeCallingTheModel() {
        SearchDecisionService delegate = query -> {
            throw new AssertionError("model should not be called");
        };
        SearchDecisionService service = new FastPathSearchDecisionService(
                delegate, new RuleBasedSearchDecisionService(null));

        var decision = service.decide(
                "แล้วเวอร์ชันใหม่ล่ะ",
                "user: ตอนนี้ Java ล่าสุดคือเวอร์ชันอะไร\nassistant: ...");

        assertTrue(decision.shouldSearch());
        assertTrue(decision.query().contains("Java"));
    }
}
