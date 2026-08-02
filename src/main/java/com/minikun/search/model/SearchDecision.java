package com.minikun.search.model;

import java.util.Objects;

public record SearchDecision(boolean shouldSearch, String query, SearchDecisionReason reason) {
    public SearchDecision(boolean shouldSearch, String query) {
        this(shouldSearch, query, SearchDecisionReason.GENERAL_KNOWLEDGE);
    }

    public SearchDecision {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        query = query.trim();
        if (shouldSearch && query.isBlank()) {
            throw new IllegalArgumentException("search query must not be blank");
        }
    }
}