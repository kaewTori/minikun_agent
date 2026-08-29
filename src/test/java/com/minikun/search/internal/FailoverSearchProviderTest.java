package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.minikun.search.SearchProvider;
import com.minikun.search.SearchProviderUnavailableException;
import com.minikun.search.model.ImageSearchResult;
import com.minikun.search.model.SearchProviderResponse;
import com.minikun.search.model.SearchOptions;
import com.minikun.search.model.SearchRequest;
import com.minikun.search.model.SearchResult;
import com.minikun.search.model.SearchSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FailoverSearchProviderTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void fallsBackToSecondaryWhenPrimaryFails() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        SearchProvider primary = request -> {
            primaryCalls.incrementAndGet();
            throw new SearchProviderUnavailableException("rate limited");
        };
        SearchProvider fallback = request -> {
            fallbackCalls.incrementAndGet();
            return new SearchProviderResponse(List.of(result("fallback")));
        };
        FailoverSearchProvider provider = new FailoverSearchProvider(
                primary, fallback, CLOCK, Duration.ofMinutes(2), 3, new SimpleMeterRegistry());

        assertEquals("fallback", provider.search(request()).results().getFirst().title());
        assertEquals(1, primaryCalls.get());
        assertEquals(1, fallbackCalls.get());
    }

    @Test
    void opensCircuitAfterThresholdAndSkipsPrimaryDuringCooldown() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        SearchProvider primary = request -> {
            primaryCalls.incrementAndGet();
            throw new SearchProviderUnavailableException("unavailable");
        };
        SearchProvider fallback = request -> {
            fallbackCalls.incrementAndGet();
            return new SearchProviderResponse(List.of(result("fallback")));
        };
        FailoverSearchProvider provider = new FailoverSearchProvider(
                primary, fallback, CLOCK, Duration.ofMinutes(2), 3, new SimpleMeterRegistry());

        provider.search(request());
        provider.search(request());
        provider.search(request());
        provider.search(request());

        assertEquals(3, primaryCalls.get());
        assertEquals(4, fallbackCalls.get());
    }

    @Test
    void fallsBackWhenImageRequestOnlyGetsTextResultsFromPrimary() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        SearchProvider primary = request -> {
            primaryCalls.incrementAndGet();
            return new SearchProviderResponse(List.of(result("text-only")));
        };
        SearchProvider fallback = request -> {
            fallbackCalls.incrementAndGet();
            return new SearchProviderResponse(List.of(), List.of(new ImageSearchResult(
                    "https://images.example/rena.jpg", "RenaRaziel artwork",
                    "https://pixiv.example/rena", "Illustration")));
        };
        FailoverSearchProvider provider = new FailoverSearchProvider(
                primary, fallback, CLOCK, Duration.ofMinutes(2), 3, new SimpleMeterRegistry());

        var response = provider.search(imageRequest());

        assertEquals(1, primaryCalls.get());
        assertEquals(1, fallbackCalls.get());
        assertEquals("https://images.example/rena.jpg", response.images().getFirst().url());
    }

    private static SearchRequest request() {
        return new SearchRequest(UUID.randomUUID(), "java records", 5, CLOCK.instant().plusSeconds(60));
    }

    private static SearchRequest imageRequest() {
        return new SearchRequest(UUID.randomUUID(), "RenaRaziel artwork", 5,
                CLOCK.instant().plusSeconds(60),
                new SearchOptions("all", SearchOptions.IMAGE_CATEGORY, "", false), List.of());
    }

    private static SearchResult result(String title) {
        String url = "https://example.com/" + title;
        return new SearchResult(title, url, "content",
                new SearchSource("fallback", url, CLOCK.instant()), 1);
    }
}
