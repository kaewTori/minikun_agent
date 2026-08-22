package com.minikun.research;

import java.util.List;
import java.util.Objects;

public record ResearchEvaluation(
        boolean sufficient,
        List<String> unresolvedGaps,
        List<String> followUpQueries) {
    public ResearchEvaluation {
        unresolvedGaps = clean(unresolvedGaps, 8);
        followUpQueries = clean(followUpQueries, 4);
    }

    public static ResearchEvaluation incomplete(String gap) {
        return new ResearchEvaluation(false, List.of(Objects.requireNonNullElse(gap, "unknown gap")), List.of());
    }

    private static List<String> clean(List<String> values, int limit) {
        return values == null ? List.of() : values.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(limit)
                .toList();
    }
}
