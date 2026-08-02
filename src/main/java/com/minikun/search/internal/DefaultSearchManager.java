package com.minikun.search.internal;

import com.minikun.search.SearchException;
import com.minikun.search.SearchManager;
import com.minikun.search.SearchProvider;
import com.minikun.search.SearchRetryExhaustedException;
import com.minikun.search.SearchTimeoutException;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.model.SearchMetadata;
import com.minikun.search.model.SearchProviderResponse;
import com.minikun.search.model.SearchRequest;
import com.minikun.search.model.SearchResponse;
import com.minikun.search.model.SearchStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DefaultSearchManager implements SearchManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSearchManager.class);
    private static final String SEARCH_TIMER = "minikun.search.duration";
    private static final String FAILURE_COUNTER = "minikun.search.failures";
    private static final String TIMEOUT_COUNTER = "minikun.search.timeouts";
    private static final String QUALITY_COUNTER = "minikun.search.quality.search.results";

    private final SearchProvider provider;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final int maxRetries;
    private final SearchDeduplicator deduplicator;
    private final SearchBudgeter budgeter;
    private final SearchFormatter formatter;

    public DefaultSearchManager(
            SearchProvider provider,
            Clock clock,
            int maxRetries,
            SearchDeduplicator deduplicator,
            SearchBudgeter budgeter,
            SearchFormatter formatter) {
        this(provider, clock, maxRetries, deduplicator, budgeter, formatter, null);
    }

    public DefaultSearchManager(
            SearchProvider provider,
            Clock clock,
            int maxRetries,
            SearchDeduplicator deduplicator,
            SearchBudgeter budgeter,
            SearchFormatter formatter,
            MeterRegistry meterRegistry) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.meterRegistry = meterRegistry;
        this.deduplicator = Objects.requireNonNull(deduplicator, "deduplicator must not be null");
        this.budgeter = Objects.requireNonNull(budgeter, "budgeter must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("max retries must not be negative");
        }
        this.maxRetries = maxRetries;
    }

    @Override
    public KnowledgeContext search(SearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Timer.Sample sample = startTimer();
        Instant started = clock.instant();
        SearchProviderResponse providerResponse = null;
        RuntimeException failure = null;
        try {
            Execution execution = execute(request);
            providerResponse = execution.response();
            Duration duration = Duration.between(started, clock.instant());
            SearchStatus status = execution.response().results().isEmpty()
                    ? SearchStatus.NO_RESULTS
                    : SearchStatus.SUCCESS;
            SearchMetadata metadata = new SearchMetadata(duration, false, false, execution.retryCount());
            SearchResponse response = new SearchResponse(
                request.requestId(), status, execution.response().results(), metadata);
            KnowledgeContext result = formatter.format(budgeter.budget(deduplicator.deduplicate(response)));
            recordQuality(result);
            return result;
        } catch (RuntimeException exception) {
            failure = exception;
            recordFailure(exception);
            throw exception;
        } finally {
            recordTimer(sample);
            logExecution(request, providerResponse, failure, Duration.between(started, clock.instant()));
        }
    }

    private Timer.Sample startTimer() {
        try {
            return Timer.start(meterRegistry);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void recordTimer(Timer.Sample sample) {
        if (sample == null) {
            return;
        }
        try {
            sample.stop(meterRegistry.timer(SEARCH_TIMER, "provider", providerName()));
        } catch (RuntimeException ignored) {
            // Observability must not affect search execution.
        }
    }

    private void recordFailure(RuntimeException exception) {
        try {
            if (exception instanceof SearchTimeoutException) {
                Counter.builder(TIMEOUT_COUNTER)
                        .tag("provider", providerName())
                        .register(meterRegistry)
                        .increment();
            } else {
                Counter.builder(FAILURE_COUNTER)
                        .tag("provider", providerName())
                        .tag("failure_type", exception.getClass().getSimpleName())
                        .register(meterRegistry)
                        .increment();
            }
        } catch (RuntimeException ignored) {
            // Observability must not affect search execution.
        }
    }

    private void recordQuality(KnowledgeContext result) {
        try {
            Counter.builder(QUALITY_COUNTER)
                    .tag("outcome", result == null || result.content().isBlank() ? "empty" : "non_empty")
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException ignored) {
            // Observability must not affect search execution.
        }
    }

    private void logExecution(
            SearchRequest request,
            SearchProviderResponse response,
            RuntimeException failure,
            Duration duration) {
        try {
            LOGGER.debug(
                "Search manager execution provider={} request_id={} duration_ms={} result_count={} timeout={} fail_open={}",
                    providerName(), request.requestId(), duration.toMillis(),
                    response == null ? 0 : response.results().size(),
                failure instanceof SearchTimeoutException, failure != null);
        } catch (RuntimeException ignored) {
            // Logging must not affect search execution.
        }
    }

    private String providerName() {
        return provider.getClass().getSimpleName();
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