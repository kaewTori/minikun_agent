package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.search.SearchCacheKey;
import com.minikun.search.model.SearchOptions;
import org.junit.jupiter.api.Test;

class SearchCacheKeyTest {
    @Test
    void normalizesEquivalentQueriesAndUsesVersionedNamespace() {
        SearchCacheKey first = SearchCacheKey.from("  Latest   NEWS ", 10);
        SearchCacheKey second = SearchCacheKey.from("latest news", 10);

        assertEquals(first, second);
        String key = ValkeySearchCache.redisKey(first);
        assertTrue(key.startsWith("minikun:search:v2:"));
        assertEquals("minikun:search:v2:".length() + 64, key.length());
    }

    @Test
    void resultLimitChangesTheKey() {
        assertTrue(!ValkeySearchCache.redisKey(SearchCacheKey.from("query", 10))
                .equals(ValkeySearchCache.redisKey(SearchCacheKey.from("query", 20))));
    }

    @Test
    void imageAndTextCategoriesUseDifferentKeys() {
        SearchCacheKey text = SearchCacheKey.from("query", 10);
        SearchCacheKey images = SearchCacheKey.from(
                "query", 10, new SearchOptions("", SearchOptions.IMAGE_CATEGORY, "", false));

        assertTrue(!ValkeySearchCache.redisKey(text).equals(ValkeySearchCache.redisKey(images)));
    }
}
