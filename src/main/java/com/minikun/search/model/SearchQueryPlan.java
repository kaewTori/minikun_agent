package com.minikun.search.model;

import java.util.List;
import java.util.Objects;

public record SearchQueryPlan(
        boolean shouldSearch,
        String originalQuery,
        String primaryQuery,
        List<String> alternateQueries,
        List<String> coreTerms,
        String language,
        String intent,
        String timeRange,
        double confidence,
        String reason,
        List<String> evidenceNeeds,
        String location) {
    public static final int MAX_ALTERNATES = 2;

    public SearchQueryPlan {
        originalQuery = requireText(originalQuery, "original query");
        primaryQuery = shouldSearch ? requireText(primaryQuery, "primary query") : "";
        final String primary = primaryQuery;
        alternateQueries = alternateQueries == null ? List.of() : alternateQueries.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .filter(value -> !value.equalsIgnoreCase(primary))
                .distinct()
                .limit(MAX_ALTERNATES)
                .toList();
        coreTerms = coreTerms == null ? List.of() : coreTerms.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        language = Objects.requireNonNullElse(language, "").trim();
        intent = Objects.requireNonNullElse(intent, "general").trim();
        timeRange = Objects.requireNonNullElse(timeRange, "").trim();
        reason = Objects.requireNonNullElse(reason, "").trim();
        evidenceNeeds = evidenceNeeds == null ? List.of() : evidenceNeeds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .limit(6)
                .toList();
        location = Objects.requireNonNullElse(location, "").trim();
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }

    public SearchQueryPlan(
            boolean shouldSearch,
            String originalQuery,
            String primaryQuery,
            List<String> alternateQueries,
            List<String> coreTerms,
            String language,
            String intent,
            String timeRange,
            double confidence,
            String reason) {
        this(shouldSearch, originalQuery, primaryQuery, alternateQueries, coreTerms, language,
                intent, timeRange, confidence, reason, List.of(), "");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
