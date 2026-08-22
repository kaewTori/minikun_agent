package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.search.SearchDecisionService;
import com.minikun.search.model.SearchDecisionReason;
import org.junit.jupiter.api.Test;

class FastPathSearchDecisionServiceTest {
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
}
