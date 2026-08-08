package com.minikun.search.model;

import com.minikun.search.InvalidSearchRequestException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SearchRequest(
        UUID requestId,
        String query,
        int resultLimit,
        Instant deadline,
        SearchOptions options,
        List<String> alternateQueries) {
    public static final int MAX_RESULT_LIMIT = 100;

    public SearchRequest {
        Objects.requireNonNull(requestId, "request id must not be null");
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(deadline, "deadline must not be null");
        options = options == null ? SearchOptions.defaults() : options;
        alternateQueries = alternateQueries == null ? List.of() : alternateQueries.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .filter(value -> !value.equalsIgnoreCase(query.trim()))
                .distinct()
                .limit(2)
                .toList();
        if (query.isBlank()) {
            throw new InvalidSearchRequestException("query must not be blank");
        }
        if (resultLimit < 1 || resultLimit > MAX_RESULT_LIMIT) {
            throw new InvalidSearchRequestException(
                    "result limit must be between 1 and " + MAX_RESULT_LIMIT);
        }
    }

    public SearchRequest(UUID requestId, String query, int resultLimit, Instant deadline) {
        this(requestId, query, resultLimit, deadline, SearchOptions.defaults(), List.of());
    }
}
