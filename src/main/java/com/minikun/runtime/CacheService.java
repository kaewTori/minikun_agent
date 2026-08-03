package com.minikun.runtime;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public final class CacheService {
    private final String enabled;
    private final String backend;
    private final Duration ttl;

    public CacheService(
            @Value("${minikun.search.cache.enabled:}") String enabled,
            @Value("${minikun.runtime.cache.backend:valkey}") String backend,
            @Value("${minikun.search.cache.ttl:}") Duration ttl) {
        this.enabled = enabled;
        this.backend = backend;
        this.ttl = ttl;
    }

    public CacheInfo snapshot() {
        return new CacheInfo(
                value(enabled),
                value(backend),
                ttl == null ? RuntimeValue.notConfigured() : RuntimeValue.configured(ttl.toString()));
    }

    private RuntimeValue value(String raw) {
        return raw == null || raw.isBlank()
                ? RuntimeValue.notConfigured()
                : RuntimeValue.configured(raw);
    }
}
