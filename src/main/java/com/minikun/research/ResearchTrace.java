package com.minikun.research;

import java.util.List;
import java.util.Objects;

/** Auditable summary of the bounded autonomous loop; it contains no hidden reasoning. */
public record ResearchTrace(
        String objective,
        List<String> subquestions,
        List<String> executedQueries,
        int iterations,
        ResearchStopReason stopReason,
        List<String> unresolvedGaps,
        boolean autonomous) {
    public static final ResearchTrace EMPTY = new ResearchTrace(
            "", List.of(), List.of(), 0, ResearchStopReason.DISABLED, List.of(), false);

    public ResearchTrace {
        objective = Objects.requireNonNullElse(objective, "").strip();
        subquestions = immutableStrings(subquestions);
        executedQueries = immutableStrings(executedQueries);
        Objects.requireNonNull(stopReason, "stop reason must not be null");
        unresolvedGaps = immutableStrings(unresolvedGaps);
        if (iterations < 0) {
            throw new IllegalArgumentException("iterations must not be negative");
        }
    }

    public String promptSummary() {
        if (!autonomous) {
            return "";
        }
        return "Autonomous research iterations: " + iterations
                + "\nStop reason: " + stopReason
                + "\nExecuted queries: " + String.join(" | ", executedQueries)
                + (unresolvedGaps.isEmpty() ? "" : "\nUnresolved evidence gaps: " + String.join(" | ", unresolvedGaps));
    }

    private static List<String> immutableStrings(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .toList();
    }
}
