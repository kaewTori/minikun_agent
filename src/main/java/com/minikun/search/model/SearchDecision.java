package com.minikun.search.model;

import java.util.Objects;

public record SearchDecision(boolean shouldSearch, String query) {
    public SearchDecision {
        Objects.requireNonNull(query, "query must not be null");
        query = query.trim();
        if (shouldSearch && query.isBlank()) {
            throw new IllegalArgumentException("search query must not be blank");
        }
    }
}