package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.minikun.search.model.ImageSearchRequest;
import com.minikun.search.model.ImageSearchResult;
import com.minikun.search.model.SearchProviderResponse;

class DefaultImageSearchServiceTest {
    @Test
    void reusesGalleryFormattingAndAddsSourceEvidence() {
        var provider = (com.minikun.search.ImageSearchProvider) request -> new SearchProviderResponse(
                List.of(), List.of(
                        new ImageSearchResult(
                                "https://images.example/match.jpg", "Match", "https://source.example/page",
                                "same subject", "https://images.example/thumb.jpg", 640, 480, "provider", "")));
        var service = new DefaultImageSearchService(provider, new SearchFormatter());

        var result = service.search(new ImageSearchRequest(
                UUID.randomUUID(), new byte[] {1}, "image/jpeg", 3,
                Instant.now(Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC)).plusSeconds(60)));

        assertEquals(1, result.images().size());
        assertEquals("https://images.example/match.jpg", result.images().getFirst().url());
        assertEquals(1, result.candidates().size());
        assertTrue(result.content().contains("same subject"));
        assertEquals("https://source.example/page", result.candidates().getFirst().provenance());
    }
}
