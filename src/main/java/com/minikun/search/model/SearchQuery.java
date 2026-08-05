package com.minikun.search.model;

import java.util.Objects;

public record SearchQuery(String originalQuery, String rewrittenQuery) {
    public SearchQuery {
        Objects.requireNonNull(originalQuery, "original query must not be null");
        Objects.requireNonNull(rewrittenQuery, "rewritten query must not be null");
    }
}