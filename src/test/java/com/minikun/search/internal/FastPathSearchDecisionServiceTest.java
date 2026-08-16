package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
