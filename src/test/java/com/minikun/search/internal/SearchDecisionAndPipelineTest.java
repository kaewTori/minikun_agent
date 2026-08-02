package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.model.SearchMetadata;
import com.minikun.search.model.SearchResponse;
import com.minikun.search.model.SearchResult;
import com.minikun.search.model.SearchSource;
import com.minikun.search.model.SearchStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchDecisionAndPipelineTest {
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final SearchMetadata METADATA = new SearchMetadata(Duration.ofMillis(10), false, false, 0);

    @Test
    void ruleBasedDecisionIsDeterministicAndConservative() {
        RuleBasedSearchDecisionService service = new RuleBasedSearchDecisionService();

        assertTrue(service.decide("What is the latest Java release?").shouldSearch());
        assertTrue(service.decide("ข่าววันนี้เป็นอย่างไร").shouldSearch());
        assertTrue(service.decide("แนะนำร้านราเมง Tonkotsu Classic").shouldSearch());
        assertFalse(service.decide("Explain dependency injection").shouldSearch());
        assertFalse(service.decide(" ").shouldSearch());
        assertEquals("latest Java", service.decide("  latest Java  ").query());
    }

    @Test
    void deduplicatorPreservesFirstOrderStatusAndMetadata() {
        SearchResult first = result("Title", "https://example.com/article#one", "Same content");
        SearchResult duplicateUrl = result("Other", "https://example.com/article#two", "Different");
        SearchResult duplicateContent = result("Title", "https://other.example/article", " same   content ");
        SearchResult distinct = result("Distinct", "https://other.example/distinct", "Different content");
        SearchResponse input = new SearchResponse(
                REQUEST_ID, SearchStatus.SUCCESS,
                List.of(first, duplicateUrl, duplicateContent, distinct), METADATA);

        SearchResponse output = new SearchDeduplicator().deduplicate(input);

        assertEquals(List.of(first, distinct), output.results());
        assertEquals(SearchStatus.SUCCESS, output.status());
        assertSame(METADATA, output.metadata());
        assertNotSame(input, output);
    }

    @Test
    void budgeterCreatesNewResultAndKeepsSuccessfulStatusWhenContentIsRemoved() {
        SearchResult result = result("A long title", "https://example.com/a", "A very long body");
        SearchResponse input = new SearchResponse(REQUEST_ID, SearchStatus.SUCCESS, List.of(result), METADATA);

        SearchResponse output = new SearchBudgeter(10).budget(input);

        assertTrue(output.results().isEmpty());
        assertEquals(SearchStatus.SUCCESS, output.status());
        assertSame(METADATA, output.metadata());
        assertNotSame(input, output);
    }

    @Test
    void formatterProducesCanonicalEmptyKnowledgeContext() {
        SearchFormatter formatter = new SearchFormatter();

        KnowledgeContext empty = formatter.format(
                new SearchResponse(REQUEST_ID, SearchStatus.SUCCESS, List.of(), METADATA));

        assertEquals("", empty.content());
    }

    private static SearchResult result(String title, String uri, String content) {
        return new SearchResult(title, uri, content,
                new SearchSource("searxng", uri, Instant.parse("2026-08-02T00:00:00Z")), 1);
    }
}