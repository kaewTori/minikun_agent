package com.minikun.search.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record ExpandedSearchQuery(
        String originalQuery,
        String rewrittenQuery,
        List<String> expandedQueries) {
    public ExpandedSearchQuery {
        Objects.requireNonNull(originalQuery, "original query must not be null");
        Objects.requireNonNull(rewrittenQuery, "rewritten query must not be null");
        Objects.requireNonNull(expandedQueries, "expanded queries must not be null");
        if (expandedQueries.isEmpty()) {
            throw new IllegalArgumentException("expanded queries must not be empty");
        }
        if (expandedQueries.get(0) == null) {
            throw new NullPointerException("expanded query must not be null");
        }
        if (!rewrittenQuery.equals(expandedQueries.get(0))) {
            throw new IllegalArgumentException("first expanded query must equal rewritten query");
        }
        for (String expandedQuery : expandedQueries) {
            Objects.requireNonNull(expandedQuery, "expanded query must not be null");
        }
        originalQuery = new String(originalQuery);
        rewrittenQuery = new String(rewrittenQuery);
        List<String> copiedQueries = new ArrayList<>(expandedQueries.size());
        for (String expandedQuery : expandedQueries) {
            copiedQueries.add(new String(expandedQuery));
        }
        expandedQueries = List.copyOf(copiedQueries);
    }
}