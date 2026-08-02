package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.search.SearchDecisionClient;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class LlmSearchDecisionServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void usesApplicationClockAndReturnsRemoteDecision() {
        SearchDecisionClient client = prompt -> {
            assertEquals("2026-08-02", prompt.currentDate());
            assertEquals("latest Java", prompt.userMessage());
            return new SearchDecision(true, prompt.userMessage(), SearchDecisionReason.CURRENT_INFORMATION);
        };
        var service = new LlmSearchDecisionService(
                client, new RuleBasedSearchDecisionService(new SimpleMeterRegistry()), CLOCK,
                new SearchDecisionPromptBuilder(), new SimpleMeterRegistry());

        SearchDecision decision = service.decide("latest Java");

        assertTrue(decision.shouldSearch());
        assertEquals(SearchDecisionReason.CURRENT_INFORMATION, decision.reason());
    }

    @Test
    void ownsFailOpenFallbackAndMarksInternalReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SearchDecisionClient client = prompt -> {
            throw new RuntimeException("router unavailable");
        };
        var service = new LlmSearchDecisionService(
            client, new RuleBasedSearchDecisionService(new SimpleMeterRegistry()), CLOCK,
                new SearchDecisionPromptBuilder(), registry);

        SearchDecision decision = service.decide("latest Java");

        assertTrue(decision.shouldSearch());
        assertEquals(SearchDecisionReason.RULE_FALLBACK, decision.reason());
        assertEquals(1, registry.get("minikun.search.decision.duration").timer().count());
    }

    @Test
    void fallbackPreservesRuleDecisionForNonSearchQuery() {
        SearchDecisionClient client = prompt -> {
            throw new RuntimeException("malformed response");
        };
        var service = new LlmSearchDecisionService(
            client, new RuleBasedSearchDecisionService(new SimpleMeterRegistry()), CLOCK,
            new SearchDecisionPromptBuilder(), new SimpleMeterRegistry());

        SearchDecision decision = service.decide("Explain dependency injection");

        assertFalse(decision.shouldSearch());
        assertEquals(SearchDecisionReason.RULE_FALLBACK, decision.reason());
    }
}