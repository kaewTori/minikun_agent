package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.search.SearchDecisionProvider;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import com.minikun.search.model.SearchPlanHints;
import java.util.List;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class LlmSearchDecisionServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void usesApplicationClockAndReturnsRemoteDecision() {
        SearchDecisionProvider client = prompt -> {
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
        SearchDecisionProvider client = prompt -> {
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
        SearchDecisionProvider client = prompt -> {
            throw new RuntimeException("malformed response");
        };
        var service = new LlmSearchDecisionService(
            client, new RuleBasedSearchDecisionService(new SimpleMeterRegistry()), CLOCK,
            new SearchDecisionPromptBuilder(), new SimpleMeterRegistry());

        SearchDecision decision = service.decide("Explain dependency injection");

        assertFalse(decision.shouldSearch());
        assertEquals(SearchDecisionReason.RULE_FALLBACK, decision.reason());
    }

    @Test
    void keepsResolvedQuerySeparateFromPromptContext() {
        SearchDecisionProvider client = prompt -> new SearchDecision(
                true, prompt.userMessage(), SearchDecisionReason.CURRENT_INFORMATION);
        var service = new LlmSearchDecisionService(
                client, new RuleBasedSearchDecisionService(null), CLOCK,
                new SearchDecisionPromptBuilder(), new SimpleMeterRegistry());

        SearchDecision decision = service.decide(
                "Java latest version", "user: previous topic\nassistant: previous answer");

        assertEquals("Java latest version", decision.query());
    }

    @Test
    void reusesRecentDecisionForTheSameNormalizedRequest() {
        AtomicInteger calls = new AtomicInteger();
        SearchDecisionProvider client = prompt -> {
            calls.incrementAndGet();
            return new SearchDecision(false, prompt.userMessage(), SearchDecisionReason.GENERAL_KNOWLEDGE);
        };
        var service = new LlmSearchDecisionService(
                client, new RuleBasedSearchDecisionService(null), CLOCK,
                new SearchDecisionPromptBuilder(), new SimpleMeterRegistry());

        service.decide("Explain dependency injection");
        service.decide("  explain   dependency injection ");

        assertEquals(1, calls.get());
    }

    @Test
    void cachedDecisionPreservesSemanticPlan() {
        SearchPlanHints hints = new SearchPlanHints(
                "local_discovery", 0.9, "ร้านข้าว ไฟฉาย", List.of("ร้านข้าว ไฟฉาย รีวิว"),
                List.of("rating", "location"), "ไฟฉาย");
        SearchDecisionProvider client = prompt -> new SearchDecision(
                true, prompt.userMessage(), SearchDecisionReason.EXTERNAL_RESOURCE, hints);
        var service = new LlmSearchDecisionService(
                client, new RuleBasedSearchDecisionService(null), CLOCK,
                new SearchDecisionPromptBuilder(), new SimpleMeterRegistry());

        service.decide("แนะนำร้านข้าวแถวไฟฉาย");
        SearchDecision cached = service.decide("แนะนำร้านข้าวแถวไฟฉาย");

        assertEquals(hints, cached.planHints());
    }
}
