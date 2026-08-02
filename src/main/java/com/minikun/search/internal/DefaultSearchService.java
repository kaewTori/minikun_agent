package com.minikun.search.internal;

import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.SearchCache;
import com.minikun.search.SearchCacheKey;
import com.minikun.search.SearchManager;
import com.minikun.search.SearchService;
import com.minikun.search.model.SearchRequest;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public final class DefaultSearchService implements SearchService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSearchService.class);
    private static final String REQUEST_COUNTER = "minikun.search.requests";
    private static final String BYPASS_COUNTER = "minikun.search.cache.bypass";
    private static final String SEARCH_TIMER = "minikun.search.duration";

    private final SearchManager manager;
    private final SearchCache cache;
    private final boolean searchEnabled;
    private final boolean cacheEnabled;
    private final MeterRegistry meterRegistry;

    public DefaultSearchService(
            SearchManager manager, SearchCache cache, boolean searchEnabled, boolean cacheEnabled) {
        this(manager, cache, searchEnabled, cacheEnabled, null);
    }

    public DefaultSearchService(
            SearchManager manager, SearchCache cache, boolean searchEnabled, boolean cacheEnabled,
            MeterRegistry meterRegistry) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
        this.searchEnabled = searchEnabled;
        this.cacheEnabled = cacheEnabled;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public KnowledgeContext search(SearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Timer.Sample sample = startTimer();
        increment(REQUEST_COUNTER);
        try {
            if (!searchEnabled) {
                increment(BYPASS_COUNTER);
                return KnowledgeContext.empty();
            }
            SearchCacheKey key = SearchCacheKey.from(request.query(), request.resultLimit());
            if (cacheEnabled) {
                try {
                    Optional<KnowledgeContext> cached = cache.get(key);
                    if (cached.isPresent()) {
                        return cached.get();
                    }
                } catch (RuntimeException exception) {
                    LOGGER.warn("Search cache lookup failed; continuing with live search", exception);
                }
            } else {
                increment(BYPASS_COUNTER);
            }
            KnowledgeContext context = manager.search(request);
            if (cacheEnabled) {
                try {
                    cache.put(key, context);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Search cache insertion failed; returning live search result", exception);
                }
            }
            return context;
        } finally {
            recordTimer(sample);
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
            sample.stop(meterRegistry.timer(SEARCH_TIMER));
        } catch (RuntimeException ignored) {
            // Observability must not affect search execution.
        }
    }

    private void increment(String name) {
        try {
            Counter.builder(name).register(meterRegistry).increment();
        } catch (RuntimeException ignored) {
            // Observability must not affect search execution.
        }
    }
}