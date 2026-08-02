package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.SearchCache;
import com.minikun.search.SearchCacheKey;
import com.minikun.search.SearchManager;
import com.minikun.search.model.SearchRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultSearchServiceTest {
    private static final SearchRequest REQUEST = new SearchRequest(
            UUID.randomUUID(), "  Latest   News  ", 10, Instant.parse("2026-08-02T00:01:00Z"));

    @Test
    void returnsCachedContextWithoutExecutingManager() {
        KnowledgeContext cached = new KnowledgeContext("cached");
        RecordingCache cache = new RecordingCache(Optional.of(cached));
        SearchManager manager = request -> {
            throw new AssertionError("manager must not be called on cache hit");
        };

        KnowledgeContext result = new DefaultSearchService(manager, cache, true, true).search(REQUEST);

        assertSame(cached, result);
    }

    @Test
    void cacheMissExecutesManagerAndStoresResult() {
        KnowledgeContext live = new KnowledgeContext("live");
        RecordingCache cache = new RecordingCache(Optional.empty());
        AtomicInteger calls = new AtomicInteger();
        SearchManager manager = request -> {
            calls.incrementAndGet();
            return live;
        };

        KnowledgeContext result = new DefaultSearchService(manager, cache, true, true).search(REQUEST);

        assertSame(live, result);
        assertEquals(1, calls.get());
        assertSame(live, cache.stored);
    }

    @Test
    void disabledSearchReturnsEmptyWithoutTouchingCacheOrManager() {
        RecordingCache cache = new RecordingCache(Optional.of(new KnowledgeContext("cached")));
        SearchManager manager = request -> {
            throw new AssertionError("manager must not be called when search is disabled");
        };

        KnowledgeContext result = new DefaultSearchService(manager, cache, false, true).search(REQUEST);

        assertEquals(KnowledgeContext.empty(), result);
        assertEquals(0, cache.getCalls);
        assertEquals(0, cache.putCalls);
    }

    @Test
    void cacheFailuresDoNotChangeLiveSearchResult() {
        KnowledgeContext live = new KnowledgeContext("live");
        SearchCache failingCache = new SearchCache() {
            @Override
            public Optional<KnowledgeContext> get(SearchCacheKey key) {
                throw new IllegalStateException("Valkey unavailable");
            }

            @Override
            public void put(SearchCacheKey key, KnowledgeContext context) {
                throw new IllegalStateException("Valkey unavailable");
            }
        };

        KnowledgeContext result = new DefaultSearchService(request -> live, failingCache, true, true)
                .search(REQUEST);

        assertSame(live, result);
    }

    @Test
    void knowledgeContextSerializationIsDeterministic() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        assertEquals(
                mapper.writeValueAsString(new KnowledgeContext("same")),
                mapper.writeValueAsString(new KnowledgeContext("same")));
    }

    private static final class RecordingCache implements SearchCache {
        private final Optional<KnowledgeContext> value;
        private int getCalls;
        private int putCalls;
        private KnowledgeContext stored;

        private RecordingCache(Optional<KnowledgeContext> value) {
            this.value = value;
        }

        @Override
        public Optional<KnowledgeContext> get(SearchCacheKey key) {
            getCalls++;
            return value;
        }

        @Override
        public void put(SearchCacheKey key, KnowledgeContext context) {
            putCalls++;
            stored = context;
        }
    }
}