package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.SearchCacheKey;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ValkeySearchCacheTest {
    private static final SearchCacheKey KEY = SearchCacheKey.from("query", 10);
    private static final Duration TTL = Duration.ofMinutes(5);

    @Test
    void writesOnlyKnowledgeContextWithNativeTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ValkeySearchCache cache = new ValkeySearchCache(redis, new ObjectMapper(), TTL);

        cache.put(KEY, new KnowledgeContext("content"));

        verify(values).set(
                anyString(),
            eq(assertSerialized(new KnowledgeContext("content"))),
            eq(TTL));
        verifyNoMoreInteractions(values);
    }

    @Test
    void insertionFailureIsFailOpenAndUsesOneAtomicWriteCall() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        org.mockito.Mockito.doThrow(new IllegalStateException("Valkey unavailable"))
                .when(values).set(anyString(), anyString(), org.mockito.Mockito.eq(TTL));
        ValkeySearchCache cache = new ValkeySearchCache(redis, new ObjectMapper(), TTL);

        assertDoesNotThrow(() -> cache.put(KEY, new KnowledgeContext("content")));
        verify(values).set(anyString(), anyString(), eq(TTL));
        verifyNoMoreInteractions(values);
    }

    @Test
    void missingEntryIsSilentNormalMiss() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        ValkeySearchCache cache = new ValkeySearchCache(redis, new ObjectMapper(), TTL);

        assertEquals(Optional.empty(), cache.get(KEY));
        verify(values).get(anyString());
        verifyNoMoreInteractions(values);
    }

    private static String assertSerialized(KnowledgeContext context) {
        return "{\"content\":\"" + context.content() + "\"}";
    }
}