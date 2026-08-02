package com.minikun.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.minikun.search.model.SearchMetadata;
import com.minikun.search.model.SearchProviderResponse;
import com.minikun.search.model.SearchRequest;
import com.minikun.search.model.SearchResponse;
import com.minikun.search.model.SearchResult;
import com.minikun.search.model.SearchSource;
import com.minikun.search.model.SearchStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchContractsTest {
    @Test
    void searchRequestValidatesItsBoundary() {
        UUID requestId = UUID.randomUUID();
        Instant deadline = Instant.now().plusSeconds(30);

        assertThrows(InvalidSearchRequestException.class,
                () -> new SearchRequest(requestId, " ", 10, deadline));
        assertThrows(InvalidSearchRequestException.class,
                () -> new SearchRequest(requestId, "query", 0, deadline));
        assertThrows(InvalidSearchRequestException.class,
                () -> new SearchRequest(requestId, "query", SearchRequest.MAX_RESULT_LIMIT + 1, deadline));
    }

    @Test
    void responseAndProviderResponseDefensivelyCopyResults() {
        SearchResult result = result();
        List<SearchResult> results = new ArrayList<>(List.of(result));
        SearchMetadata metadata = new SearchMetadata(Duration.ofMillis(10), false, false, 0);

        SearchProviderResponse providerResponse = new SearchProviderResponse(results);
        SearchResponse response = new SearchResponse(
                UUID.randomUUID(), SearchStatus.SUCCESS, results, metadata);
        results.clear();

        assertEquals(List.of(result), providerResponse.results());
        assertEquals(List.of(result), response.results());
        assertThrows(UnsupportedOperationException.class,
                () -> response.results().add(result));
    }

    @Test
    void emptyResultsUseNoResultsStatus() {
        SearchResponse response = new SearchResponse(
                UUID.randomUUID(),
                SearchStatus.NO_RESULTS,
                List.of(),
                new SearchMetadata(Duration.ZERO, false, false, 0));

        assertEquals(SearchStatus.NO_RESULTS, response.status());
        assertThrows(IllegalArgumentException.class, () -> new SearchResponse(
                UUID.randomUUID(),
                SearchStatus.NO_RESULTS,
                List.of(result()),
                new SearchMetadata(Duration.ZERO, false, false, 0)));
    }

    private static SearchResult result() {
        SearchSource source = new SearchSource(
                "searxng", "https://example.com/source", Instant.now());
        return new SearchResult(
                "Title", "https://example.com/result", "Content", source, 0);
    }
}