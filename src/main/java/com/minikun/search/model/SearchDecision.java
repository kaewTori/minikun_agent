package com.minikun.search.model;

import java.util.Objects;

public record SearchDecision(
        boolean shouldSearch, String query, SearchDecisionReason reason, SearchPlanHints planHints) {
    public SearchDecision(boolean shouldSearch, String query) {
        this(shouldSearch, query, SearchDecisionReason.GENERAL_KNOWLEDGE, SearchPlanHints.EMPTY);
    }

    public SearchDecision(boolean shouldSearch, String query, SearchDecisionReason reason) {
        this(shouldSearch, query, reason, SearchPlanHints.EMPTY);
    }

    public SearchDecision {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        planHints = planHints == null ? SearchPlanHints.EMPTY : planHints;
        query = query.trim();
        if (shouldSearch && query.isBlank()) {
            throw new IllegalArgumentException("search query must not be blank");
        }
    }
}
