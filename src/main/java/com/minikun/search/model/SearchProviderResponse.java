package com.minikun.search.model;

import java.util.List;
import java.util.Objects;

public record SearchProviderResponse(List<SearchResult> results, List<ImageSearchResult> images) {
    public SearchProviderResponse(List<SearchResult> results) {
        this(results, List.of());
    }

    public SearchProviderResponse {
        Objects.requireNonNull(results, "results must not be null");
        results = List.copyOf(results);
        images = images == null ? List.of() : List.copyOf(images);
    }
}