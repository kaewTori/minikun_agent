package com.minikun.search.internal;

import com.minikun.search.SearchException;
import com.minikun.search.SearchManager;
import com.minikun.search.SearchProvider;
import com.minikun.search.SearchRetryExhaustedException;
import com.minikun.search.SearchTimeoutException;
import com.minikun.search.model.SearchMetadata;
import com.minikun.search.model.SearchProviderResponse;
import com.minikun.search.model.SearchRequest;
import com.minikun.search.model.SearchResponse;
import com.minikun.search.model.SearchStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class DefaultSearchManager implements SearchManager {
    private final SearchProvider provider;
    private final Clock clock;
    private final int maxRetries;

    public DefaultSearchManager(SearchProvider provider, Clock clock, int maxRetries) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("max retries must not be negative");
        }
        this.maxRetries = maxRetries;
    }

    @Override
    public SearchResponse search(SearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Instant started = clock.instant();
        Execution execution = execute(request);
        Duration duration = Duration.between(started, clock.instant());
        SearchStatus status = execution.response().results().isEmpty()
                ? SearchStatus.NO_RESULTS
                : SearchStatus.SUCCESS;
        SearchMetadata metadata = new SearchMetadata(duration, false, false, execution.retryCount());
        return new SearchResponse(request.requestId(), status, execution.response().results(), metadata);
    }

    private Execution execute(SearchRequest request) {
        SearchException lastFailure = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            ensureWithinDeadline(request);
            try {
                SearchProviderResponse response = provider.search(request);
                ensureWithinDeadline(request);
                return new Execution(response, attempt);
            } catch (SearchTimeoutException exception) {
                throw exception;
            } catch (SearchException exception) {
                lastFailure = exception;
            }
        }
        throw new SearchRetryExhaustedException("SearXNG search retries exhausted", lastFailure);
    }

    private record Execution(SearchProviderResponse response, int retryCount) {
    }

    private void ensureWithinDeadline(SearchRequest request) {
        if (!clock.instant().isBefore(request.deadline())) {
            throw new SearchTimeoutException("search deadline exceeded");
        }
    }
}