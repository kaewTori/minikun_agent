package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.SearchCache;
import com.minikun.search.SearchCacheKey;
import com.minikun.search.SearchManager;
import com.minikun.search.SearchQueryExpansionService;
import com.minikun.search.SearchQueryRewriteService;
import com.minikun.search.model.ExpandedSearchQuery;
import com.minikun.search.model.SearchRequest;
import com.minikun.search.model.SearchQuery;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
    void emptyCachedResultIsRetriedAndEmptyLiveResultIsNotCached() {
        RecordingCache cache = new RecordingCache(Optional.of(KnowledgeContext.empty()));
        AtomicInteger calls = new AtomicInteger();
        SearchManager manager = request -> {
            calls.incrementAndGet();
            return KnowledgeContext.empty();
        };

        KnowledgeContext result = new DefaultSearchService(manager, cache, true, true).search(REQUEST);

        assertEquals(KnowledgeContext.empty(), result);
        assertEquals(1, calls.get());
        assertEquals(0, cache.putCalls);
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
    void rewriteRunsOnceAndManagerAndCacheUseRewrittenQuery() {
        KnowledgeContext live = new KnowledgeContext("live");
        RecordingCache cache = new RecordingCache(Optional.empty());
        AtomicInteger rewriteCalls = new AtomicInteger();
        SearchQueryRewriteService rewriteService = query -> {
            rewriteCalls.incrementAndGet();
            return new SearchQuery(query, "canonical query");
        };
        AtomicInteger managerCalls = new AtomicInteger();
        SearchManager manager = request -> {
            managerCalls.incrementAndGet();
            assertEquals("canonical query", request.query());
            return live;
        };

        KnowledgeContext result = new DefaultSearchService(
                manager, cache, true, true, rewriteService, new SimpleMeterRegistry()).search(REQUEST);

        assertSame(live, result);
        assertEquals(1, rewriteCalls.get());
        assertEquals(1, managerCalls.get());
        assertEquals(SearchCacheKey.from("canonical query", REQUEST.resultLimit()), cache.storedKey);
    }

    @Test
    void structuralRewriteReachesManagerCacheAndMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KnowledgeContext live = new KnowledgeContext("live");
        RecordingCache cache = new RecordingCache(Optional.empty());
        SearchRequest request = new SearchRequest(
                UUID.randomUUID(), "\uFEFF\tＡ\u200B  News  ", 10,
                Instant.parse("2026-08-02T00:01:00Z"));
        SearchManager manager = searchRequest -> {
            assertEquals("A News", searchRequest.query());
            return live;
        };

        KnowledgeContext result = new DefaultSearchService(
                manager, cache, true, true, new DefaultSearchQueryRewriteService(), registry)
                .search(request);

        assertSame(live, result);
        assertEquals(SearchCacheKey.from("A News", request.resultLimit()), cache.storedKey);
        assertEquals(1.0, registry.get("minikun.search.rewrite.requests").counter().count());
        assertEquals(1.0, registry.get("minikun.search.rewrite.changed").counter().count());
    }

    @Test
    void disabledSearchDoesNotInvokeRewrite() {
        AtomicInteger rewriteCalls = new AtomicInteger();
        SearchQueryRewriteService rewriteService = query -> {
            rewriteCalls.incrementAndGet();
            return new SearchQuery(query, query);
        };

        new DefaultSearchService(
                request -> KnowledgeContext.empty(), new RecordingCache(Optional.empty()),
                false, true, rewriteService, new SimpleMeterRegistry()).search(REQUEST);

        assertEquals(0, rewriteCalls.get());
    }

    @Test
    void disabledSearchDoesNotInvokeExpansion() {
        AtomicInteger expansionCalls = new AtomicInteger();
        SearchQueryExpansionService expansionService = query -> {
            expansionCalls.incrementAndGet();
            return new ExpandedSearchQuery(query.originalQuery(), query.rewrittenQuery(),
                    List.of(query.rewrittenQuery()));
        };

        new DefaultSearchService(
                request -> KnowledgeContext.empty(), new RecordingCache(Optional.empty()),
                false, true, new DefaultSearchQueryRewriteService(), expansionService,
                new SimpleMeterRegistry()).search(REQUEST);

        assertEquals(0, expansionCalls.get());
    }

    @Test
    void rewriteMetricsCountRequestsAndOnlyChangedQueries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SearchQueryRewriteService rewriteService = query -> new SearchQuery(query, "canonical query");
        SearchManager manager = request -> new KnowledgeContext("live");

        new DefaultSearchService(
                manager, new RecordingCache(Optional.empty()), true, false,
                rewriteService, registry).search(REQUEST);

        assertEquals(1.0, registry.get("minikun.search.rewrite.requests").counter().count());
        assertEquals(1.0, registry.get("minikun.search.rewrite.changed").counter().count());
    }

    @Test
    void identityRewriteDoesNotIncrementChangedMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SearchManager manager = request -> new KnowledgeContext("live");
        SearchRequest normalizedRequest = new SearchRequest(
            UUID.randomUUID(), "Latest News", 10, Instant.parse("2026-08-02T00:01:00Z"));

        new DefaultSearchService(
                manager, new RecordingCache(Optional.empty()), true, false,
                new com.minikun.search.internal.DefaultSearchQueryRewriteService(), registry)
            .search(normalizedRequest);

        assertEquals(1.0, registry.get("minikun.search.rewrite.requests").counter().count());
        assertNull(registry.find("minikun.search.rewrite.changed").counter());
    }

    @Test
    void expansionRunsOnceManagerReceivesExpandedQueriesAndMetricsDescribeChange() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger expansionCalls = new AtomicInteger();
        List<String> receivedQueries = new java.util.ArrayList<>();
        SearchQueryExpansionService expansionService = query -> {
            expansionCalls.incrementAndGet();
            return new ExpandedSearchQuery(
                    query.originalQuery(), query.rewrittenQuery(),
                    List.of(query.rewrittenQuery(), "alternate"));
        };
        SearchManager manager = new SearchManager() {
            @Override
            public KnowledgeContext search(SearchRequest request) {
                return new KnowledgeContext("legacy");
            }

            @Override
            public KnowledgeContext search(SearchRequest request, ExpandedSearchQuery expandedQuery) {
                receivedQueries.addAll(expandedQuery.expandedQueries());
                return new KnowledgeContext("live");
            }
        };

        new DefaultSearchService(
                manager, new RecordingCache(Optional.empty()), true, false,
                new DefaultSearchQueryRewriteService(), expansionService, registry).search(REQUEST);

        assertEquals(1, expansionCalls.get());
        assertEquals(List.of("Latest News", "alternate"), receivedQueries);
        assertEquals(1.0, registry.get("minikun.search.expand.requests").counter().count());
        assertEquals(1.0, registry.get("minikun.search.expand.changed").counter().count());
    }

    @Test
    void canonicalAndSynonymQueriesReachManagerAndCache() {
        KnowledgeContext live = new KnowledgeContext("live");
        RecordingCache cache = new RecordingCache(Optional.empty());
        List<String> receivedQueries = new java.util.ArrayList<>();
        SearchManager manager = new SearchManager() {
            @Override
            public KnowledgeContext search(SearchRequest request) {
                return new KnowledgeContext("legacy");
            }

            @Override
            public KnowledgeContext search(SearchRequest request, ExpandedSearchQuery expandedQuery) {
                receivedQueries.addAll(expandedQuery.expandedQueries());
                return live;
            }
        };
        SearchQueryExpansionService expansionService = query -> new ExpandedSearchQuery(
                query.originalQuery(), query.rewrittenQuery(),
                List.of(query.rewrittenQuery(), "synonym"));

        assertSame(live, new DefaultSearchService(
                manager, cache, true, true, new DefaultSearchQueryRewriteService(),
                expansionService, new SimpleMeterRegistry()).search(REQUEST));

        assertEquals(List.of("Latest News", "synonym"), receivedQueries);
        assertEquals(List.of(
                SearchCacheKey.from("Latest News", REQUEST.resultLimit()),
                SearchCacheKey.from("synonym", REQUEST.resultLimit())), cache.storedKeys);
    }

    @Test
    void multiQueryCacheHitPreservesCandidateProvenanceForResearchReading() {
        var candidate = new com.minikun.pcs.KnowledgeCandidate(
                "search-0", com.minikun.pcs.KnowledgeSource.SEARCH,
                "Report (https://example.org/report): evidence", 0,
                "https://example.org/report");
        KnowledgeContext cached = new KnowledgeContext("cached evidence", List.of(candidate));
        RecordingCache cache = new RecordingCache(Optional.of(cached));
        SearchQueryExpansionService expansionService = query -> new ExpandedSearchQuery(
                query.originalQuery(), query.rewrittenQuery(),
                List.of(query.rewrittenQuery(), "official source"));
        SearchManager manager = request -> {
            throw new AssertionError("manager must not be called on a complete cache hit");
        };

        KnowledgeContext result = new DefaultSearchService(
                manager, cache, true, true, new DefaultSearchQueryRewriteService(),
                expansionService, new SimpleMeterRegistry()).search(REQUEST);

        assertEquals(1, result.candidates().size());
        assertEquals("https://example.org/report", result.candidates().getFirst().provenance());
    }

    @Test
    void canonicalSynonymAndAcronymQueriesReachManagerAndCacheInOrder() {
        KnowledgeContext live = new KnowledgeContext("live");
        RecordingCache cache = new RecordingCache(Optional.empty());
        List<String> receivedQueries = new java.util.ArrayList<>();
        SearchManager manager = new SearchManager() {
            @Override
            public KnowledgeContext search(SearchRequest request) {
                return new KnowledgeContext("legacy");
            }

            @Override
            public KnowledgeContext search(SearchRequest request, ExpandedSearchQuery expandedQuery) {
                receivedQueries.addAll(expandedQuery.expandedQueries());
                return live;
            }
        };
        SearchQueryExpansionService expansionService = query -> new ExpandedSearchQuery(
                query.originalQuery(), query.rewrittenQuery(),
                List.of(query.rewrittenQuery(), "synonym", "acronym"));

        assertSame(live, new DefaultSearchService(
                manager, cache, true, true, new DefaultSearchQueryRewriteService(),
                expansionService, new SimpleMeterRegistry()).search(REQUEST));

        assertEquals(List.of("Latest News", "synonym", "acronym"), receivedQueries);
        assertEquals(List.of(
                SearchCacheKey.from("Latest News", REQUEST.resultLimit()),
                SearchCacheKey.from("synonym", REQUEST.resultLimit()),
                SearchCacheKey.from("acronym", REQUEST.resultLimit())), cache.storedKeys);
    }

    @Test
    void freshExpansionCopiesDoNotIncrementChangedMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SearchQueryExpansionService expansionService = query -> new ExpandedSearchQuery(
                new String(query.originalQuery()), new String(query.rewrittenQuery()),
                List.of(new String(query.rewrittenQuery())));

        new DefaultSearchService(
                request -> new KnowledgeContext("live"), new RecordingCache(Optional.empty()),
                true, false, new DefaultSearchQueryRewriteService(), expansionService, registry)
                .search(new SearchRequest(
                        UUID.randomUUID(), "Latest News", 10,
                        Instant.parse("2026-08-02T00:01:00Z")));

        assertEquals(1.0, registry.get("minikun.search.expand.requests").counter().count());
        assertNull(registry.find("minikun.search.expand.changed").counter());
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
        private SearchCacheKey storedKey;
        private final List<SearchCacheKey> storedKeys = new java.util.ArrayList<>();

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
            storedKey = key;
            storedKeys.add(key);
            stored = context;
        }
    }
}
