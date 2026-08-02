package com.minikun.search.model;

import java.time.Instant;
import java.util.Objects;

public record SearchSource(
        String name,
        String canonicalUri,
        Instant retrievedAt) {
    public SearchSource {
        Objects.requireNonNull(name, "source name must not be null");
        Objects.requireNonNull(canonicalUri, "source URI must not be null");
        Objects.requireNonNull(retrievedAt, "retrieved at must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("source name must not be blank");
        }
        if (canonicalUri.isBlank()) {
            throw new IllegalArgumentException("source URI must not be blank");
        }
    }
}