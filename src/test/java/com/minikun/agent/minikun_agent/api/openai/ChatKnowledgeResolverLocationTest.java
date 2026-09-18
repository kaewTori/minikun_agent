package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.minikun.pcs.DefaultKnowledgeConsolidationService;
import com.minikun.pcs.DefaultKnowledgeSelectionService;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.SearchSelectionSignalMapper;
import com.minikun.search.internal.DefaultSearchContextAwarenessService;
import com.minikun.search.internal.DefaultSearchQueryPlanningService;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import com.minikun.search.model.SearchRequest;
import com.minikun.search.model.SearchPlanHints;
import com.minikun.weather.DeviceLocation;
import com.minikun.weather.ReverseGeocodingService;
import com.minikun.vision.VisionInput;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ChatKnowledgeResolverLocationTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void addsVerifiedAreaToNearbySearchWithoutExposingCoordinates() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://geo.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        "https://geo.test/reverse?lat=13.7326&lon=100.5291&format=jsonv2&zoom=14"
                                + "&addressdetails=1&accept-language=th"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("""
                        {"address":{"neighbourhood":"สามย่าน","city_district":"ปทุมวัน",
                        "city":"กรุงเทพมหานคร","country":"ประเทศไทย","country_code":"th"}}
                        """, MediaType.APPLICATION_JSON));
        ReverseGeocodingService reverseGeocoder = new ReverseGeocodingService(
                builder.build(), new com.fasterxml.jackson.databind.ObjectMapper(), CLOCK,
                Duration.ofMinutes(10), 250, "MinikunAgent/1.0", true);
        AtomicReference<SearchRequest> observed = new AtomicReference<>();
        ObjectProvider<com.minikun.memory.MemoryRecallService> memory = mock(ObjectProvider.class);
        com.minikun.search.SearchService search = request -> {
            observed.set(request);
            return KnowledgeContext.fromCandidates(List.of(new KnowledgeCandidate(
                    "search-0", KnowledgeSource.SEARCH,
                    "ร้านอาหาร สามย่าน รีวิว 4.5 ดาว เปิด 10:00 ราคา 100 บาท ที่อยู่แผนที่ "
                            + "(https://example.org/place)", 0, "https://example.org/place")));
        };
        SearchPlanHints hints = new SearchPlanHints(
                "local_discovery", 0.96, "ร้านอาหาร", List.of(),
                List.of("opening_hours", "rating", "location", "price"), "");
        ChatKnowledgeResolver resolver = new ChatKnowledgeResolver(
                memory, null, null, search,
                query -> new SearchDecision(true, query, SearchDecisionReason.EXTERNAL_RESOURCE, hints),
                new DefaultSearchQueryPlanningService(), new DefaultSearchContextAwarenessService(),
                new DefaultKnowledgeSelectionService(), new DefaultKnowledgeConsolidationService(),
                new SearchSelectionSignalMapper(), null, null, null, reverseGeocoder,
                new ChatKnowledgeResolver.Configuration(true, Duration.ofSeconds(5), true, true,
                        8, 5, 5, 3, Duration.ofSeconds(30)));

        ChatKnowledgeSelection result = resolver.resolve(new ChatKnowledgeResolver.Request(
                "แนะนำร้านอาหารแถวนี้ให้เราหน่อย", "location-test", null, "default", false, "",
                null, 0, VisionInput.EMPTY, "", List.of(),
                new DeviceLocation(13.732639, 100.529052, 5.0, CLOCK.millis() - 1_000)));

        assertTrue(observed.get().query().contains("สามย่าน, ปทุมวัน"));
        assertTrue(!observed.get().query().contains("13.732639"));
        assertEquals("สามย่าน, ปทุมวัน", result.deviceLocationContext().area());
        assertTrue(result.deviceLocationContext().requested());
        server.verify();
    }
}
