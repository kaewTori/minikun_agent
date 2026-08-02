package com.minikun.search.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SearchResponse(
        UUID requestId,
        SearchStatus status,
        List<SearchResult> results,
        SearchMetadata metadata) {
    public SearchResponse {
        Objects.requireNonNull(requestId, "request id must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(results, "results must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        results = List.copyOf(results);
        if (status == SearchStatus.NO_RESULTS && !results.isEmpty()) {
            throw new IllegalArgumentException("no-results response must not contain results");
        }
        if (status == SearchStatus.SUCCESS && results.isEmpty()) {
            throw new IllegalArgumentException("successful response must contain results");
        }
    }
}