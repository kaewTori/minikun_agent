package com.minikun.search;

import java.util.Objects;

public record SearchCacheKey(String normalizedQuery, int maximumResultCount) {
    public SearchCacheKey {
        Objects.requireNonNull(normalizedQuery, "normalized query must not be null");
        if (normalizedQuery.isBlank()) {
            throw new IllegalArgumentException("normalized query must not be blank");
        }
        if (maximumResultCount < 1) {
            throw new IllegalArgumentException("maximum result count must be positive");
        }
    }

    public static SearchCacheKey from(String query, int maximumResultCount) {
        Objects.requireNonNull(query, "query must not be null");
        String normalized = query.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        return new SearchCacheKey(normalized, maximumResultCount);
    }
}