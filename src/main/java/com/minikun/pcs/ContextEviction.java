package com.minikun.pcs;

import java.util.Objects;

public record ContextEviction(ContextItem item, ContextEvictionReason reason) {
    public ContextEviction {
        Objects.requireNonNull(item, "item must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}