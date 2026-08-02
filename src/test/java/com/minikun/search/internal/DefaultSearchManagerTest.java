package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.SearchProvider;
import com.minikun.search.SearchProviderUnavailableException;
import com.minikun.search.model.SearchProviderResponse;
import com.minikun.search.model.SearchRequest;
import com.minikun.search.model.SearchResult;
import com.minikun.search.model.SearchSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

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

    private static DefaultSearchManager manager(SearchProvider provider, int maxRetries) {
        return new DefaultSearchManager(
                provider,
                CLOCK,
                maxRetries,
                new SearchDeduplicator(),
                new SearchBudgeter(4000),
                new SearchFormatter());
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
}