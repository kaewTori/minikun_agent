package com.minikun.runtime;

public record CacheInfo(
        RuntimeValue enabled,
        RuntimeValue backend,
        RuntimeValue ttl) {
}
