package com.minikun.search.model;

import java.util.List;
import java.util.Objects;

public record SearchProviderResponse(List<SearchResult> results) {
    public SearchProviderResponse {
        Objects.requireNonNull(results, "results must not be null");
        results = List.copyOf(results);
    }
}