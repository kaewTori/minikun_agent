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

public final class DefaultSearchService implements SearchService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSearchService.class);

    private final SearchManager manager;
    private final SearchCache cache;
    private final boolean searchEnabled;
    private final boolean cacheEnabled;

    public DefaultSearchService(
            SearchManager manager, SearchCache cache, boolean searchEnabled, boolean cacheEnabled) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
        this.searchEnabled = searchEnabled;
        this.cacheEnabled = cacheEnabled;
    }

    @Override
    public KnowledgeContext search(SearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!searchEnabled) {
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
    }
}