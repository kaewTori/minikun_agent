package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.pcs.model.ImageSource;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.search.SearchCacheKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
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

    @Test
    void roundTripsImagesThroughValkeyCache() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ValkeySearchCache cache = new ValkeySearchCache(redis, new ObjectMapper(), TTL);
        KnowledgeContext original = new KnowledgeContext("content", List.of(new KnowledgeCandidate("news",
                KnowledgeSource.SEARCH, "News", 0, "https://news.example", Instant.parse("2026-10-06T01:00:00Z"), 0.95)), List.of(
                new ImageSource("https://images.example/one.jpg", "One", "source-one", "Description one"),
                new ImageSource("https://images.example/two.jpg", "Two", "source-two", "Description two")));

        cache.put(KEY, original);

        ArgumentCaptor<String> serialized = ArgumentCaptor.forClass(String.class);
        verify(values).set(anyString(), serialized.capture(), eq(TTL));
        when(values.get(anyString())).thenReturn(serialized.getValue());
        KnowledgeContext restored = cache.get(KEY).orElseThrow();

        assertEquals(original.content(), restored.content());
        assertEquals(original.candidates(), restored.candidates());
        assertEquals(original.images(), restored.images());
        assertEquals("https://images.example/one.jpg", restored.images().getFirst().url());
        assertEquals("One", restored.images().getFirst().title());
        assertEquals("source-one", restored.images().getFirst().sourceUrl());
        assertEquals("Description one", restored.images().getFirst().description());
        assertThrows(UnsupportedOperationException.class, () -> restored.images().clear());
    }

    @Test
    void readsLegacyEntriesWithoutImagesThroughValkeyCache() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("{\"content\":\"text\"}");
        ValkeySearchCache cache = new ValkeySearchCache(redis, new ObjectMapper(), TTL);

        KnowledgeContext restored = cache.get(KEY).orElseThrow();

        assertEquals("text", restored.content());
        assertEquals(List.of(), restored.images());
        assertThrows(UnsupportedOperationException.class, () -> restored.images().clear());
    }

    private static String assertSerialized(KnowledgeContext context) {
        return "{\"content\":\"" + context.content() + "\",\"candidates\":[],\"images\":[]}";
    }
}
