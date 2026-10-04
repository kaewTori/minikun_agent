package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.search.model.SearchRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TavilySearchProviderTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void mapsTavilyResultsAndSendsBearerToken() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://tavily.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TavilySearchProvider provider = new TavilySearchProvider(
                builder.defaultHeader("Authorization", "Bearer test-key").build(),
                new ObjectMapper(), CLOCK, "test-key", true, "basic");
        server.expect(requestTo("https://tavily.test/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().json("""
                        {"query":"java records","search_depth":"basic","max_results":5,
                         "include_answer":false,"include_raw_content":false,"include_images":false}
                        """))
                .andRespond(withSuccess("""
                        {"results":[{"title":"Java Records","url":"https://example.com/java",
                        "content":"Immutable data carrier","score":0.9,
                        "published_date":"2026-08-01T12:00:00Z"}]}
                        """, MediaType.APPLICATION_JSON));

        var response = provider.search(request("java records", 5));

        assertEquals(1, response.results().size());
        assertEquals("Java Records", response.results().getFirst().title());
        assertEquals("tavily", response.results().getFirst().source().name());
        assertEquals(Instant.parse("2026-08-01T12:00:00Z"), response.results().getFirst().publishedAt());
        server.verify();
    }

    private static SearchRequest request(String query, int limit) {
        return new SearchRequest(UUID.randomUUID(), query, limit, CLOCK.instant().plusSeconds(60));
    }
}
