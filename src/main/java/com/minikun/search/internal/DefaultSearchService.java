package com.minikun.search.internal;

import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.SearchCache;
import com.minikun.search.SearchCacheKey;
import com.minikun.search.SearchManager;
import com.minikun.search.SearchQueryExpansionService;
import com.minikun.search.SearchQueryRewriteService;
import com.minikun.search.SearchService;
import com.minikun.search.model.ExpandedSearchQuery;
import com.minikun.search.model.SearchRequest;
import com.minikun.search.model.SearchQuery;
import java.util.ArrayList;
import java.util.List;
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
    private static final String REWRITE_REQUEST_COUNTER = "minikun.search.rewrite.requests";
    private static final String REWRITE_CHANGED_COUNTER = "minikun.search.rewrite.changed";
    private static final String EXPAND_REQUEST_COUNTER = "minikun.search.expand.requests";
    private static final String EXPAND_CHANGED_COUNTER = "minikun.search.expand.changed";
    private static final String SEARCH_TIMER = "minikun.search.duration";

    private final SearchManager manager;
    private final SearchCache cache;
    private final boolean searchEnabled;
    private final boolean cacheEnabled;
    private final SearchQueryRewriteService queryRewriteService;
    private final SearchQueryExpansionService queryExpansionService;
    private final MeterRegistry meterRegistry;

    public DefaultSearchService(
            SearchManager manager, SearchCache cache, boolean searchEnabled, boolean cacheEnabled) {
        this(manager, cache, searchEnabled, cacheEnabled, new DefaultSearchQueryRewriteService(), null);
    }

    public DefaultSearchService(
            SearchManager manager, SearchCache cache, boolean searchEnabled, boolean cacheEnabled,
            MeterRegistry meterRegistry) {
        this(manager, cache, searchEnabled, cacheEnabled, new DefaultSearchQueryRewriteService(), meterRegistry);
        }

        public DefaultSearchService(
            SearchManager manager, SearchCache cache, boolean searchEnabled, boolean cacheEnabled,
            SearchQueryRewriteService queryRewriteService, MeterRegistry meterRegistry) {
        this(manager, cache, searchEnabled, cacheEnabled, queryRewriteService,
                new DefaultSearchQueryExpansionService(), meterRegistry);
    }

    public DefaultSearchService(
            SearchManager manager, SearchCache cache, boolean searchEnabled, boolean cacheEnabled,
            SearchQueryRewriteService queryRewriteService,
            SearchQueryExpansionService queryExpansionService,
            MeterRegistry meterRegistry) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
        this.searchEnabled = searchEnabled;
        this.cacheEnabled = cacheEnabled;
        this.queryRewriteService = Objects.requireNonNull(
            queryRewriteService, "query rewrite service must not be null");
        this.queryExpansionService = Objects.requireNonNull(
            queryExpansionService, "query expansion service must not be null");
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
            SearchQuery searchQuery = queryRewriteService.rewrite(request.query());
            increment(REWRITE_REQUEST_COUNTER);
            if (!searchQuery.originalQuery().equals(searchQuery.rewrittenQuery())) {
                increment(REWRITE_CHANGED_COUNTER);
            }
                ExpandedSearchQuery expandedSearchQuery = queryExpansionService.expand(searchQuery);
                increment(EXPAND_REQUEST_COUNTER);
                if (!expandedSearchQuery.expandedQueries().equals(List.of(expandedSearchQuery.rewrittenQuery()))) {
                increment(EXPAND_CHANGED_COUNTER);
                }
            SearchRequest rewrittenRequest = new SearchRequest(
                    request.requestId(), expandedSearchQuery.rewrittenQuery(),
                    request.resultLimit(), request.deadline());
                List<SearchCacheKey> keys = expandedSearchQuery.expandedQueries().stream()
                        .map(query -> SearchCacheKey.from(query, request.resultLimit()))
                        .toList();
            if (cacheEnabled) {
                    List<KnowledgeContext> cachedContexts = new ArrayList<>();
                    boolean allCached = true;
                    for (SearchCacheKey key : keys) {
                        try {
                            Optional<KnowledgeContext> cached = cache.get(key);
                            if (cached.isPresent()) {
                                cachedContexts.add(cached.get());
                            } else {
                                allCached = false;
                            }
                        } catch (RuntimeException exception) {
                            allCached = false;
                            LOGGER.warn("Search cache lookup failed; continuing with live search", exception);
                    }
                    }
                    if (allCached) {
                        if (cachedContexts.size() == 1) {
                            return cachedContexts.get(0);
                        }
                        return new KnowledgeContext(cachedContexts.stream()
                                .map(KnowledgeContext::content)
                                .filter(content -> !content.isBlank())
                                .reduce((left, right) -> left + "\n" + right)
                                .orElse(""));
                }
            } else {
                increment(BYPASS_COUNTER);
            }
            KnowledgeContext context = manager.search(rewrittenRequest, expandedSearchQuery);
            if (cacheEnabled) {
                for (SearchCacheKey key : keys) {
                    try {
                        cache.put(key, context);
                    } catch (RuntimeException exception) {
                        LOGGER.warn("Search cache insertion failed; returning live search result", exception);
                    }
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