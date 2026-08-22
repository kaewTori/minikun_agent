package com.minikun.research;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record AutonomousResearchRequest(
        String userQuery,
        String conversationContext,
        String primaryQuery,
        List<String> seedQueries,
        String language,
        String timeRange,
        boolean safeSearch,
        int resultLimit,
        int sourceReadLimit,
        Instant deadline) {
    public AutonomousResearchRequest {
        userQuery = requireText(userQuery, "user query");
        conversationContext = Objects.requireNonNullElse(conversationContext, "");
        primaryQuery = requireText(primaryQuery, "primary query");
        seedQueries = seedQueries == null ? List.of() : seedQueries.stream()
                .filter(Objects::nonNull).map(String::strip).filter(value -> !value.isBlank()).distinct().toList();
        language = Objects.requireNonNullElse(language, "all");
        timeRange = Objects.requireNonNullElse(timeRange, "");
        Objects.requireNonNull(deadline, "deadline must not be null");
        if (resultLimit < 1 || sourceReadLimit < 0) {
            throw new IllegalArgumentException("research limits must not be negative");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
