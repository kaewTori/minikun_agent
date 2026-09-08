package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.pcs.model.ImageSource;
import com.minikun.search.SearchProvider;
import com.minikun.search.SearchProviderUnavailableException;
import com.minikun.search.model.ExpandedSearchQuery;
import com.minikun.search.model.SearchProviderResponse;
import com.minikun.search.model.SearchRequest;
import com.minikun.search.model.SearchResult;
import com.minikun.search.model.SearchSource;
import com.minikun.search.model.ImageSearchResult;
import com.minikun.search.model.SearchOptions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DefaultSearchManagerTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void returnsSuccessForProviderResults() {
        SearchProvider provider = request -> new SearchProviderResponse(List.of(result()));
        DefaultSearchManager manager = manager(provider, 0);

        KnowledgeContext response = manager.search(request());

        assertEquals("Title (https://example.com/result): Content", response.content());
    }

        @Test
        void recordsProviderTimerExactlyOnceOnSuccess() {
        var registry = new SimpleMeterRegistry();
        SearchProvider provider = request -> new SearchProviderResponse(List.of(result()));
        DefaultSearchManager manager = new DefaultSearchManager(
            provider, CLOCK, 0, new SearchDeduplicator(), new SearchBudgeter(4000),
            new SearchFormatter(), registry);

        manager.search(request());

        assertEquals(1, registry.find("minikun.search.duration").timer().count(),
            "provider timer must be recorded once");
        }

    @Test
    void returnsNoResultsForAnEmptyProviderResponse() {
        SearchProvider provider = request -> new SearchProviderResponse(List.of());
        DefaultSearchManager manager = manager(provider, 0);

        assertEquals("", manager.search(request()).content());
    }

    @Test
    void retriesProviderFailureAndReportsRetryCount() {
        AtomicInteger calls = new AtomicInteger();
        SearchProvider provider = request -> {
            if (calls.getAndIncrement() == 0) {
                throw new SearchProviderUnavailableException("temporary failure");
            }
            return new SearchProviderResponse(List.of(result()));
        };
        DefaultSearchManager manager = manager(provider, 1);

        KnowledgeContext response = manager.search(request());

        assertEquals(2, calls.get());
        assertEquals("Title (https://example.com/result): Content", response.content());
    }

    @Test
    void failsBeforeCallingProviderWhenDeadlineHasPassed() {
        SearchProvider provider = request -> {
            throw new AssertionError("provider must not be called");
        };
        DefaultSearchManager manager = manager(provider, 1);
        SearchRequest request = new SearchRequest(
                UUID.randomUUID(), "query", 10, Instant.parse("2026-08-01T23:59:59Z"));

        assertThrows(com.minikun.search.SearchTimeoutException.class,
                () -> manager.search(request));
    }

            @Test
            void executesExpandedQueriesSequentiallyAndAppendsProviderResults() {
            List<String> executedQueries = new java.util.ArrayList<>();
            SearchProvider provider = request -> {
                executedQueries.add(request.query());
                return new SearchProviderResponse(List.of(result(request.query())));
            };
            DefaultSearchManager manager = manager(provider, 0);
            SearchRequest request = request();
            ExpandedSearchQuery expanded = new ExpandedSearchQuery(
                "original", "query", List.of("query", "alternate", "third"));

            KnowledgeContext response = manager.search(request, expanded);

            assertEquals(List.of("query", "alternate", "third"), executedQueries);
            assertEquals(
                "Title query (https://example.com/query): Content query\n"
                    + "Title alternate (https://example.com/alternate): Content alternate\n"
                    + "Title third (https://example.com/third): Content third",
                response.content());
            }

            @Test
            void mapsImagesWithoutEnteringTextKnowledgePathAndAppliesLimit() {
            SearchProvider provider = request -> new SearchProviderResponse(List.of(), List.of(
                new ImageSearchResult("https://images.example/one.jpg", "One", "https://one.example", ""),
                new ImageSearchResult("https://images.example/one.jpg", "Duplicate", "https://duplicate.example", ""),
                new ImageSearchResult("https://images.example/two.jpg", "Two", "https://two.example", "")));
            DefaultSearchManager manager = manager(provider, 0);
            SearchRequest request = new SearchRequest(
                UUID.randomUUID(), "one", 1, CLOCK.instant().plusSeconds(60),
                new SearchOptions("", SearchOptions.IMAGE_CATEGORY, "", false), List.of());

            KnowledgeContext response = manager.search(request);

            assertEquals("", response.content());
            assertEquals(1, response.images().size());
            assertEquals("https://images.example/one.jpg", response.images().getFirst().url());
            }

            @Test
            void preservesImageOrderAndDeduplicatesTrimmedUrls() {
            SearchProvider provider = request -> new SearchProviderResponse(List.of(), List.of(
                new ImageSearchResult(" https://images.example/a.jpg ", "A", "source-a", "description-a"),
                new ImageSearchResult("https://images.example/b.jpg", "B", "source-b", "description-b"),
                new ImageSearchResult("https://images.example/a.jpg", "A duplicate", "source-duplicate", ""),
                new ImageSearchResult("https://images.example/c.jpg", "C", "source-c", "description-c")));
            DefaultSearchManager manager = manager(provider, 0);
            SearchRequest request = new SearchRequest(
                UUID.randomUUID(), "description", 10, CLOCK.instant().plusSeconds(60),
                new SearchOptions("", SearchOptions.IMAGE_CATEGORY, "", false), List.of());

            KnowledgeContext response = manager.search(request);

            assertEquals(List.of("https://images.example/a.jpg", "https://images.example/b.jpg",
                "https://images.example/c.jpg"),
                response.images().stream().map(ImageSource::url).toList());
            assertEquals("A", response.images().getFirst().title());
            assertEquals("source-a", response.images().getFirst().sourceUrl());
            }

        @Test
        void dropsImagesThatDoNotMatchTheImageQuery() {
            SearchProvider provider = request -> new SearchProviderResponse(List.of(), List.of(
                    new ImageSearchResult("https://images.example/cat.jpg", "Cat", "https://cats.example", "cat portrait"),
                    new ImageSearchResult("https://images.example/mountain.jpg", "Mountain", "https://mountains.example", "alpine view")));
            DefaultSearchManager manager = manager(provider, 0);
            SearchRequest request = new SearchRequest(
                    UUID.randomUUID(), "cat", 10, CLOCK.instant().plusSeconds(60),
                    new SearchOptions("", SearchOptions.IMAGE_CATEGORY, "", false), List.of());

            KnowledgeContext response = manager.search(request);

            assertEquals(List.of("https://images.example/cat.jpg"),
                    response.images().stream().map(ImageSource::url).toList());
        }

        @Test
        void returnsNoImagesWhenNoCandidateMatchesTheQuery() {
            SearchProvider provider = request -> new SearchProviderResponse(List.of(), List.of(
                    new ImageSearchResult("https://images.example/mountain.jpg", "Mountain", "https://mountains.example", "alpine view")));
            DefaultSearchManager manager = manager(provider, 0);
            SearchRequest request = new SearchRequest(
                    UUID.randomUUID(), "cat", 10, CLOCK.instant().plusSeconds(60),
                    new SearchOptions("", SearchOptions.IMAGE_CATEGORY, "", false), List.of());

            KnowledgeContext response = manager.search(request);

            assertEquals(List.of(), response.images());
        }

        @Test
        void retriesImageSearchOnceWithQuotedAnchorWhenFirstRoundHasNoRelevantImage() {
            List<String> calls = new java.util.ArrayList<>();
            SearchProvider provider = request -> {
                calls.add(request.query());
                if (calls.size() == 1) {
                    return new SearchProviderResponse(List.of(), List.of(
                            new ImageSearchResult("https://images.example/mountain.jpg", "Mountain",
                                    "https://mountains.example", "alpine view")));
                }
                return new SearchProviderResponse(List.of(), List.of(
                        new ImageSearchResult("https://images.example/tesla.jpg", "Tesla Model 3",
                                "https://cars.example/tesla", "electric car")));
            };
            DefaultSearchManager manager = manager(provider, 0);
            SearchRequest request = new SearchRequest(
                    UUID.randomUUID(), "Tesla Model 3", 6, CLOCK.instant().plusSeconds(60),
                    new SearchOptions("", SearchOptions.IMAGE_CATEGORY, "", false), List.of());

            KnowledgeContext response = manager.search(request);

            assertEquals(List.of("Tesla Model 3", "\"Tesla Model 3\""), calls);
            assertEquals(List.of("https://images.example/tesla.jpg"),
                    response.images().stream().map(ImageSource::url).toList());
        }

        @Test
        void skipsNullImageResultsBeforeNormalizingUrls() {
            SearchFormatter formatter = new SearchFormatter();

            KnowledgeContext response = formatter.formatImages(java.util.Arrays.asList(
                    null,
                    new ImageSearchResult(" https://images.example/a.jpg ", "A", "source-a", "description-a")),
                    10);

            assertEquals(List.of("https://images.example/a.jpg"),
                    response.images().stream().map(ImageSource::url).toList());
        }

            @Test
            void returnsImmutableEmptyImagesWhenImageSearchHasNoValidImages() {
            SearchProvider provider = request -> new SearchProviderResponse(List.of(result()), List.of());
            DefaultSearchManager manager = manager(provider, 0);
            SearchRequest request = new SearchRequest(
                UUID.randomUUID(), "images", 10, CLOCK.instant().plusSeconds(60),
                new SearchOptions("", SearchOptions.IMAGE_CATEGORY, "", false), List.of());

            KnowledgeContext response = manager.search(request);

            assertEquals("", response.content());
            assertEquals(List.of(), response.images());
            assertThrows(UnsupportedOperationException.class, () -> response.images().clear());
            }

    private static DefaultSearchManager manager(SearchProvider provider, int maxRetries) {
        return new DefaultSearchManager(
                provider,
                CLOCK,
                maxRetries,
                new SearchDeduplicator(),
                new SearchBudgeter(4000),
                new SearchFormatter(),
                new SimpleMeterRegistry());
    }

    private static SearchRequest request() {
        return new SearchRequest(
                UUID.randomUUID(), "query", 10, Instant.parse("2026-08-02T00:01:00Z"));
    }

    private static SearchResult result() {
        SearchSource source = new SearchSource(
            "searxng", "https://example.com/result", CLOCK.instant());
        return new SearchResult(
            "Title", "https://example.com/result", "Content", source, 1);
        }

        private static SearchResult result(String query) {
        SearchSource source = new SearchSource(
            "searxng", "https://example.com/" + query, CLOCK.instant());
        return new SearchResult(
            "Title " + query, "https://example.com/" + query, "Content " + query, source, 1);
    }
}
