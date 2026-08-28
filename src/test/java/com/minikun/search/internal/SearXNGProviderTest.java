package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.search.SearchExecutionException;
import com.minikun.search.model.SearchRequest;
import com.minikun.search.model.SearchOptions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SearXNGProviderTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void mapsSearXngResultsToCanonicalResults() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://searxng.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SearXNGProvider provider = new SearXNGProvider(builder.build(), new ObjectMapper(), CLOCK);
        server.expect(requestTo("http://searxng.test/search?q=java%20records&format=json&number_of_results=5"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"results":[{"title":"Java Records","url":"https://example.com/java",\
                        "content":"Immutable data carrier"}],"engines":["google"]}
                        """, MediaType.APPLICATION_JSON));

        var response = provider.search(request("java records", 5));

        assertEquals(1, response.results().size());
        assertEquals("Java Records", response.results().getFirst().title());
        assertEquals("searxng", response.results().getFirst().source().name());
        assertEquals(1, response.results().getFirst().sourcePosition());
        server.verify();
    }

    @Test
    void rejectsResponsesWithoutResultsArray() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://searxng.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SearXNGProvider provider = new SearXNGProvider(builder.build(), new ObjectMapper(), CLOCK);
        server.expect(requestTo("http://searxng.test/search?q=query&format=json&number_of_results=5"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThrows(SearchExecutionException.class, () -> provider.search(request("query", 5)));
        server.verify();
    }

    @Test
    void encodesJsonLikeQueriesWithoutUriTemplateExpansion() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://searxng.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SearXNGProvider provider = new SearXNGProvider(builder.build(), new ObjectMapper(), CLOCK);
        server.expect(requestTo(
                        "http://searxng.test/search?q=%7B%22title%22:%22Java%22%7D&format=json&number_of_results=5"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        provider.search(request("{\"title\":\"Java\"}", 5));

        server.verify();
    }

    @Test
    void encodesThaiQueryAndMapsUnsupportedWeekRange() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://searxng.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SearXNGProvider provider = new SearXNGProvider(builder.build(), new ObjectMapper(), CLOCK);
        server.expect(requestTo(
                        "http://searxng.test/search?q=%E0%B8%82%E0%B9%88%E0%B8%B2%E0%B8%A7%20AI&format=json&number_of_results=5&language=th&categories=news&time_range=month"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        provider.search(new SearchRequest(
                UUID.randomUUID(), "ข่าว AI", 5, CLOCK.instant().plusSeconds(60),
                new SearchOptions("th", "news", "week", false), java.util.List.of()));

        server.verify();
    }

    @Test
    void retriesServerFailureOnceWithoutOptionalFilters() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://searxng.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SearXNGProvider provider = new SearXNGProvider(builder.build(), new ObjectMapper(), CLOCK);
        server.expect(requestTo(
                        "http://searxng.test/search?q=news&format=json&number_of_results=5&language=th&categories=news&time_range=day&safesearch=1"))
                .andRespond(withServerError());
        server.expect(requestTo("http://searxng.test/search?q=news&format=json&number_of_results=5"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        var response = provider.search(new SearchRequest(
                UUID.randomUUID(), "news", 5, CLOCK.instant().plusSeconds(60),
                new SearchOptions("th", "news", "day", true), java.util.List.of()));

        assertEquals(0, response.results().size());
        server.verify();
    }

    @Test
    void mapsImageResultsAndSkipsEntriesWithoutImageUrls() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://searxng.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SearXNGProvider provider = new SearXNGProvider(builder.build(), new ObjectMapper(), CLOCK);
        server.expect(requestTo(
                        "http://searxng.test/search?q=mountains&format=json&number_of_results=5&categories=images"))
                .andRespond(withSuccess("""
                        {"results":[
                          {"img_src":"https://images.example/mountain.jpg","title":"Mountain",\
                           "url":"https://example.com/mountain","content":"Alpine view"},
                          {"title":"Malformed","url":"https://example.com/malformed"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        var response = provider.search(new SearchRequest(
                UUID.randomUUID(), "mountains", 5, CLOCK.instant().plusSeconds(60),
                new SearchOptions("", SearchOptions.IMAGE_CATEGORY, "", false), java.util.List.of()));

        assertEquals(0, response.results().size());
        assertEquals(1, response.images().size());
        assertEquals("https://images.example/mountain.jpg", response.images().getFirst().url());
        assertEquals("Mountain", response.images().getFirst().title());
        assertEquals("https://example.com/mountain", response.images().getFirst().sourceUrl());
        assertEquals("Alpine view", response.images().getFirst().description());
        server.verify();
    }

        @Test
        void mapsMissingImageMetadataToEmptyStrings() {
                RestClient.Builder builder = RestClient.builder().baseUrl("http://searxng.test");
                MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
                SearXNGProvider provider = new SearXNGProvider(builder.build(), new ObjectMapper(), CLOCK);
                server.expect(requestTo(
                                                "http://searxng.test/search?q=mountains&format=json&number_of_results=5&categories=images"))
                                .andRespond(withSuccess("""
                                                {"results":[
                                                  {"img_src":"https://images.example/mountain.jpg"},
                                                  {"img_src":42},
                                                  {"img_src":"   "}
                                                ]}
                                                """, MediaType.APPLICATION_JSON));

                var response = provider.search(new SearchRequest(
                                UUID.randomUUID(), "mountains", 5, CLOCK.instant().plusSeconds(60),
                                new SearchOptions("", SearchOptions.IMAGE_CATEGORY, "", false), java.util.List.of()));

                assertEquals(1, response.images().size());
                var image = response.images().getFirst();
                assertEquals("https://images.example/mountain.jpg", image.url());
                assertEquals("", image.title());
                assertEquals("", image.sourceUrl());
                assertEquals("", image.description());
                server.verify();
        }

    private static SearchRequest request(String query, int limit) {
        return new SearchRequest(
                UUID.randomUUID(), query, limit, CLOCK.instant().plusSeconds(60));
    }
}
