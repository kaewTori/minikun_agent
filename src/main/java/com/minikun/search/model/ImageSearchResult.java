package com.minikun.search.model;

import java.util.Objects;

public record ImageSearchResult(
        String url,
        String title,
        String sourceUrl,
        String description) {
    public ImageSearchResult {
        Objects.requireNonNull(url, "image URL must not be null");
        title = Objects.requireNonNullElse(title, "");
        sourceUrl = Objects.requireNonNullElse(sourceUrl, "");
        description = Objects.requireNonNullElse(description, "");
        if (url.isBlank()) {
            throw new IllegalArgumentException("image URL must not be blank");
        }
    }
}