package com.minikun.search.model;

import java.util.List;
import java.util.Objects;

/** Optional semantic planning output produced with the search decision. */
public record SearchPlanHints(
        String intent,
        double confidence,
        String primaryQuery,
        List<String> alternateQueries,
        List<String> evidenceNeeds,
        String location) {
    public static final SearchPlanHints EMPTY = new SearchPlanHints("", 0.0, "", List.of(), List.of(), "");

    public SearchPlanHints {
        intent = Objects.requireNonNullElse(intent, "").trim();
        primaryQuery = Objects.requireNonNullElse(primaryQuery, "").trim();
        alternateQueries = clean(alternateQueries, 2);
        evidenceNeeds = clean(evidenceNeeds, 6);
        location = Objects.requireNonNullElse(location, "").trim();
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }

    public boolean available() {
        return !primaryQuery.isBlank() || !intent.isBlank() || !evidenceNeeds.isEmpty() || !location.isBlank();
    }

    private static List<String> clean(List<String> values, int limit) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .limit(limit)
                .toList();
    }
}
