package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.search.SearchCacheKey;
import org.junit.jupiter.api.Test;

class SearchCacheKeyTest {
    @Test
    void normalizesEquivalentQueriesAndUsesVersionedNamespace() {
        SearchCacheKey first = SearchCacheKey.from("  Latest   NEWS ", 10);
        SearchCacheKey second = SearchCacheKey.from("latest news", 10);

        assertEquals(first, second);
        String key = ValkeySearchCache.redisKey(first);
        assertTrue(key.startsWith("minikun:search:v1:"));
        assertEquals("minikun:search:v1:".length() + 64, key.length());
    }

    @Test
    void resultLimitChangesTheKey() {
        assertTrue(!ValkeySearchCache.redisKey(SearchCacheKey.from("query", 10))
                .equals(ValkeySearchCache.redisKey(SearchCacheKey.from("query", 20))));
    }
}