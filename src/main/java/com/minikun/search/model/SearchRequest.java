package com.minikun.search.model;

import com.minikun.search.InvalidSearchRequestException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SearchRequest(
        UUID requestId,
        String query,
        int resultLimit,
        Instant deadline) {
    public static final int MAX_RESULT_LIMIT = 100;

    public SearchRequest {
        Objects.requireNonNull(requestId, "request id must not be null");
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(deadline, "deadline must not be null");
        if (query.isBlank()) {
            throw new InvalidSearchRequestException("query must not be blank");
        }
        if (resultLimit < 1 || resultLimit > MAX_RESULT_LIMIT) {
            throw new InvalidSearchRequestException(
                    "result limit must be between 1 and " + MAX_RESULT_LIMIT);
        }
    }
}