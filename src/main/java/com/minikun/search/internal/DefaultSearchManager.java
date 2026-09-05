package com.minikun.search.internal;

import com.minikun.search.SearchException;
import com.minikun.search.SearchManager;
import com.minikun.search.SearchProvider;
import com.minikun.search.SearchRetryExhaustedException;
import com.minikun.search.SearchTimeoutException;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.model.SearchMetadata;
import com.minikun.search.model.ImageSearchResult;
import com.minikun.search.model.SearchProviderResponse;
import com.minikun.search.model.SearchRequest;
import com.minikun.search.model.SearchOptions;
import com.minikun.search.model.SearchResponse;
import com.minikun.search.model.SearchResult;
import com.minikun.search.model.SearchStatus;
import com.minikun.search.model.ExpandedSearchQuery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
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
    private final boolean parallelQueries;
    private final int maxConcurrentQueries;

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
        this(provider, clock, maxRetries, deduplicator, budgeter, formatter, meterRegistry, false, 1);
    }

    public DefaultSearchManager(
            SearchProvider provider,
            Clock clock,
            int maxRetries,
            SearchDeduplicator deduplicator,
            SearchBudgeter budgeter,
            SearchFormatter formatter,
            MeterRegistry meterRegistry,
            boolean parallelQueries,
            int maxConcurrentQueries) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.meterRegistry = meterRegistry;
        this.deduplicator = Objects.requireNonNull(deduplicator, "deduplicator must not be null");
        this.budgeter = Objects.requireNonNull(budgeter, "budgeter must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("max retries must not be negative");
        }
        if (maxConcurrentQueries < 1) {
            throw new IllegalArgumentException("max concurrent queries must be positive");
        }
        this.maxRetries = maxRetries;
        this.parallelQueries = parallelQueries;
        this.maxConcurrentQueries = maxConcurrentQueries;
    }

    @Override
    public KnowledgeContext search(SearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return search(request, new ExpandedSearchQuery(
                request.query(), request.query(), List.of(request.query())));
    }

    @Override
    public KnowledgeContext search(SearchRequest request, ExpandedSearchQuery expandedQuery) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(expandedQuery, "expanded query must not be null");
        Timer.Sample sample = startTimer();
        Instant started = clock.instant();
        List<SearchResult> providerResults = new ArrayList<>();
        List<ImageSearchResult> providerImageResults = new ArrayList<>();
        RuntimeException failure = null;
        try {
            List<Execution> executions = executeExpandedQueries(request, expandedQuery);
            int retryCount = 0;
            for (Execution execution : executions) {
                providerResults.addAll(execution.response().results());
                providerImageResults.addAll(execution.response().images());
                retryCount += execution.retryCount();
            }
            if (isImageSearch(request)) {
                KnowledgeContext result = formatter.formatImages(
                        providerImageResults, request.resultLimit(), request.query());
                recordQuality(result);
                return result;
            }
            Duration duration = Duration.between(started, clock.instant());
            SearchProviderResponse providerResponse = new SearchProviderResponse(providerResults);
            SearchStatus status = providerResponse.results().isEmpty()
                    ? SearchStatus.NO_RESULTS
                    : SearchStatus.SUCCESS;
            SearchMetadata metadata = new SearchMetadata(duration, false, false, retryCount);
            SearchResponse response = new SearchResponse(
                request.requestId(), status, providerResponse.results(), metadata);
            SearchResponse deduplicated = deduplicator.deduplicate(response);
            SearchResponse ranked = new SearchRanker().rank(
                    deduplicated, expandedQuery.expandedQueries(), request.options());
            SearchResponse qualityFiltered = formatter.filterQuality(ranked);
            KnowledgeContext result = formatter.format(budgeter.budget(qualityFiltered), request.query());
            recordQuality(result);
            return result;
        } catch (RuntimeException exception) {
            failure = exception;
            recordFailure(exception);
            if (isImageSearch(request)) {
                return KnowledgeContext.empty();
            }
            throw exception;
        } finally {
            recordTimer(sample);
            logExecution(request, failure, providerResults.size(), Duration.between(started, clock.instant()));
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
            RuntimeException failure,
            int resultCount,
            Duration duration) {
        try {
            LOGGER.debug(
                "Search manager execution provider={} request_id={} duration_ms={} result_count={} timeout={} fail_open={}",
                    providerName(), request.requestId(), duration.toMillis(),
                    resultCount,
                failure instanceof SearchTimeoutException, failure != null);
        } catch (RuntimeException ignored) {
            // Logging must not affect search execution.
        }
    }

    private String providerName() {
        return provider.getClass().getSimpleName();
    }

    private boolean isImageSearch(SearchRequest request) {
        return SearchOptions.IMAGE_CATEGORY.equalsIgnoreCase(request.options().category());
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

    private List<Execution> executeExpandedQueries(
            SearchRequest request, ExpandedSearchQuery expandedQuery) {
        if (!parallelQueries || expandedQuery.expandedQueries().size() < 2) {
            List<Execution> executions = new ArrayList<>();
            for (String query : expandedQuery.expandedQueries()) {
                executions.add(execute(new SearchRequest(request.requestId(), query, request.resultLimit(),
                        request.deadline(), request.options(), List.of())));
            }
            return executions;
        }
        Semaphore permits = new Semaphore(maxConcurrentQueries);
        List<CompletableFuture<Execution>> futures = expandedQuery.expandedQueries().stream()
                .map(query -> CompletableFuture.supplyAsync(() -> {
                    boolean acquired = false;
                    try {
                        permits.acquire();
                        acquired = true;
                        return execute(new SearchRequest(request.requestId(), query, request.resultLimit(),
                                request.deadline(), request.options(), List.of()));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new SearchTimeoutException("parallel search execution interrupted");
                    } finally {
                        if (acquired) {
                            permits.release();
                        }
                    }
                }))
                .toList();
        List<Execution> executions = new ArrayList<>(futures.size());
        for (CompletableFuture<Execution> future : futures) {
            try {
                executions.add(future.join());
            } catch (CompletionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw exception;
            }
        }
        return executions;
    }

    private void ensureWithinDeadline(SearchRequest request) {
        if (!clock.instant().isBefore(request.deadline())) {
            throw new SearchTimeoutException("search deadline exceeded");
        }
    }
}
