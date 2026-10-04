package com.minikun.search.model;

import java.time.Instant;
import java.util.Objects;

public record SearchResult(
        String title,
        String canonicalUri,
        String content,
        SearchSource source,
        Integer sourcePosition,
        double providerScore,
        Instant publishedAt) {
    public SearchResult(
            String title,
            String canonicalUri,
            String content,
            SearchSource source,
            Integer sourcePosition) {
        this(title, canonicalUri, content, source, sourcePosition, 0.0, null);
    }

    public SearchResult(
            String title,
            String canonicalUri,
            String content,
            SearchSource source,
            Integer sourcePosition,
            double providerScore) {
        this(title, canonicalUri, content, source, sourcePosition, providerScore, null);
    }

    public SearchResult {
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(canonicalUri, "canonical URI must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(source, "source must not be null");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (canonicalUri.isBlank()) {
            throw new IllegalArgumentException("canonical URI must not be blank");
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (sourcePosition != null && sourcePosition < 0) {
            throw new IllegalArgumentException("source position must not be negative");
        }
        if (Double.isNaN(providerScore) || providerScore < 0.0 || providerScore > 1.0) {
            throw new IllegalArgumentException("provider score must be between 0 and 1");
        }
    }
}
