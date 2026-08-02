package com.minikun.search.model;

import java.util.Objects;

public record SearchResult(
        String title,
        String canonicalUri,
        String content,
        SearchSource source,
        Integer sourcePosition) {
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
    }
}