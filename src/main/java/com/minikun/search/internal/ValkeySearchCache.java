package com.minikun.search.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.SearchCache;
import com.minikun.search.SearchCacheKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Objects;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class ValkeySearchCache implements SearchCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(ValkeySearchCache.class);
    private static final String PREFIX = "minikun:search:v1:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final MeterRegistry meterRegistry;

    public ValkeySearchCache(StringRedisTemplate redis, ObjectMapper objectMapper, Duration ttl) {
        this(redis, objectMapper, ttl, null);
    }

    public ValkeySearchCache(
            StringRedisTemplate redis, ObjectMapper objectMapper, Duration ttl, MeterRegistry meterRegistry) {
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
        this.meterRegistry = meterRegistry;
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }

    @Override
    public Optional<KnowledgeContext> get(SearchCacheKey key) {
        try {
            String value = redis.opsForValue().get(redisKey(key));
            if (value == null) {
                increment("minikun.search.cache.miss");
                return Optional.empty();
            }
            increment("minikun.search.cache.hit");
            return Optional.of(objectMapper.readValue(value, KnowledgeContext.class));
        } catch (Exception exception) {
            LOGGER.warn("Search cache lookup failed; continuing without cache", exception);
            increment("minikun.search.cache.miss");
            return Optional.empty();
        }
    }

    @Override
    public void put(SearchCacheKey key, KnowledgeContext context) {
        try {
            String value = objectMapper.writeValueAsString(context);
            redis.opsForValue().set(redisKey(key), value, ttl);
            increment("minikun.search.cache.put");
        } catch (Exception exception) {
            LOGGER.warn("Search cache insertion failed; continuing without cache", exception);
        }
    }

    private void increment(String name) {
        try {
            Counter.builder(name).register(meterRegistry).increment();
        } catch (RuntimeException ignored) {
            // Observability must not affect cache behavior.
        }
    }

    static String redisKey(SearchCacheKey key) {
        String material = key.normalizedQuery() + "\u0000" + key.maximumResultCount();
        if (!"v1|||false".equals(key.optionsFingerprint())) {
            material += "\u0000" + key.optionsFingerprint();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}