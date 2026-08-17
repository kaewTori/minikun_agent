package com.minikun.search.internal;

import com.minikun.search.SearchException;
import com.minikun.search.SearchProvider;
import com.minikun.search.model.SearchProviderResponse;
import com.minikun.search.model.SearchRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Routes search to a primary provider and falls back during provider degradation. */
public final class FailoverSearchProvider implements SearchProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(FailoverSearchProvider.class);
    private static final String REQUEST_COUNTER = "minikun.search.provider.requests";
    private static final String SUCCESS_COUNTER = "minikun.search.provider.success";
    private static final String FAILURE_COUNTER = "minikun.search.provider.failures";
    private static final String FALLBACK_COUNTER = "minikun.search.provider.fallbacks";

    private final SearchProvider primary;
    private final SearchProvider fallback;
    private final Clock clock;
    private final Duration cooldown;
    private final int failureThreshold;
    private final MeterRegistry meterRegistry;
    private final Object stateLock = new Object();
    private int consecutiveFailures;
    private Instant openUntil = Instant.MIN;

    public FailoverSearchProvider(
            SearchProvider primary,
            SearchProvider fallback,
            Clock clock,
            Duration cooldown,
            int failureThreshold,
            MeterRegistry meterRegistry) {
        this.primary = Objects.requireNonNull(primary, "primary provider must not be null");
        this.fallback = Objects.requireNonNull(fallback, "fallback provider must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown must not be null");
        this.meterRegistry = meterRegistry;
        if (cooldown.isNegative() || cooldown.isZero()) {
            throw new IllegalArgumentException("cooldown must be positive");
        }
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failure threshold must be positive");
        }
        this.failureThreshold = failureThreshold;
    }

    @Override
    public SearchProviderResponse search(SearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        increment(REQUEST_COUNTER, "provider", "router");
        if (isCircuitOpen()) {
            LOGGER.info("process=search_provider event=fallback request_id={} from=primary to=fallback reason=circuit_open",
                    request.requestId());
            increment(FALLBACK_COUNTER, "reason", "circuit_open");
            return fallback.search(request);
        }

        try {
            SearchProviderResponse response = primary.search(request);
            if (isEmpty(response)) {
                recordPrimaryFailure("empty_results");
                LOGGER.info("process=search_provider event=fallback request_id={} from=primary to=fallback reason=empty_results",
                        request.requestId());
                increment(FALLBACK_COUNTER, "reason", "empty_results");
                return fallback.search(request);
            }
            recordPrimarySuccess();
            increment(SUCCESS_COUNTER, "provider", "primary");
            return response;
        } catch (SearchException exception) {
            recordPrimaryFailure(exception.getClass().getSimpleName());
            LOGGER.warn("process=search_provider event=fallback request_id={} from=primary to=fallback reason={} message={}",
                    request.requestId(), exception.getClass().getSimpleName(), exception.getMessage());
            increment(FALLBACK_COUNTER, "reason", exception.getClass().getSimpleName());
            try {
                SearchProviderResponse response = fallback.search(request);
                if (!isEmpty(response)) {
                    increment(SUCCESS_COUNTER, "provider", "fallback");
                }
                return response;
            } catch (SearchException fallbackException) {
                fallbackException.addSuppressed(exception);
                throw fallbackException;
            }
        }
    }

    private boolean isCircuitOpen() {
        synchronized (stateLock) {
            return clock.instant().isBefore(openUntil);
        }
    }

    private void recordPrimarySuccess() {
        synchronized (stateLock) {
            consecutiveFailures = 0;
            openUntil = Instant.MIN;
        }
    }

    private void recordPrimaryFailure(String reason) {
        synchronized (stateLock) {
            consecutiveFailures++;
            if (consecutiveFailures >= failureThreshold) {
                openUntil = clock.instant().plus(cooldown);
                LOGGER.warn("process=search_provider event=circuit_open provider=primary reason={} cooldown_ms={}",
                        reason, cooldown.toMillis());
            }
        }
        increment(FAILURE_COUNTER, "provider", "primary");
    }

    private boolean isEmpty(SearchProviderResponse response) {
        return response == null || (response.results().isEmpty() && response.images().isEmpty());
    }

    private void increment(String name, String tagKey, String tagValue) {
        try {
            Counter.builder(name).tag(tagKey, tagValue).register(meterRegistry).increment();
        } catch (RuntimeException ignored) {
            // Observability must not affect provider routing.
        }
    }
}
