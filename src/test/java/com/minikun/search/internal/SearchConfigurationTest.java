package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minikun.search.SearchProvider;
import com.minikun.search.SearchDecisionService;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import com.minikun.search.model.SearchPlanHints;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchConfigurationTest {
    @Test
    void llmFastPathSkipsStableRequestsButKeepsQuotedAndLiveRequestsSemantic() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        SearchDecisionService service = new SearchConfiguration().searchDecisionService(prompt -> {
            calls.incrementAndGet();
            return new SearchDecision(false, prompt.userMessage(), SearchDecisionReason.GENERAL_KNOWLEDGE);
        }, Clock.systemUTC(), new SearchDecisionPromptBuilder(), new SimpleMeterRegistry(),
                new ImageIntentDetector(), true, "llm");
        assertEquals(SearchDecisionReason.GENERAL_KNOWLEDGE, service.decide("อธิบาย DNS").reason());
        assertEquals(SearchDecisionReason.GENERAL_KNOWLEDGE, service.decide("hello").reason());
        assertEquals(0, calls.get());
        assertEquals(SearchDecisionReason.GENERAL_KNOWLEDGE, service.decide("แปลคำพูดว่า 'หาร้านอาหาร'").reason());
        service.decide("what is the best cafe near the station");
        service.decide("อธิบายต่อ", "user: ดูราคาปัจจุบัน");
        assertEquals(3, calls.get());
    }

    @Test
    void llmModeUsesSemanticDecisionForMixedRequest() {
        String query = "ขอสภาพอากาศตอนนี้ แล้วมีอะไรน่ากินมั้ง แถวนี้";
        SearchDecisionService service = new SearchConfiguration().searchDecisionService(
                prompt -> new SearchDecision(true, prompt.userMessage(),
                        SearchDecisionReason.EXTERNAL_RESOURCE,
                        new SearchPlanHints("local_discovery", 0.9, "ร้านอาหาร แถวนี้",
                                List.of(), List.of("location"), "")),
                Clock.systemUTC(), new SearchDecisionPromptBuilder(), new SimpleMeterRegistry(),
                new ImageIntentDetector(), true, "llm");

        var decision = service.decide(query);

        assertEquals("ร้านอาหาร แถวนี้", decision.planHints().primaryQuery());
        assertEquals(SearchDecisionReason.EXTERNAL_RESOURCE, decision.reason());
    }

    @Test
    void routesDirectlyToSearxngWhenTavilyIsNotConfigured() {
        TavilySearchProvider tavily = mock(TavilySearchProvider.class);
        SearchProvider searxng = mock(SearchProvider.class);
        when(tavily.configured()).thenReturn(false);

        SearchProvider selected = new SearchConfiguration().searchProvider(
                tavily, searxng, Clock.systemUTC(), new SimpleMeterRegistry(),
                true, Duration.ofMinutes(2), 3);

        assertSame(searxng, selected);
    }
}
