package com.minikun.search.model;

import java.util.Objects;

public record ImageSearchResult(
        String url,
        String title,
        String sourceUrl,
        String description,
        String thumbnailUrl,
        Integer width,
        Integer height,
        String provider,
        String license) {
    public ImageSearchResult(String url, String title, String sourceUrl, String description) {
        this(url, title, sourceUrl, description, "", null, null, "", "");
    }

    public ImageSearchResult {
        Objects.requireNonNull(url, "image URL must not be null");
        title = Objects.requireNonNullElse(title, "");
        sourceUrl = Objects.requireNonNullElse(sourceUrl, "");
        description = Objects.requireNonNullElse(description, "");
        thumbnailUrl = Objects.requireNonNullElse(thumbnailUrl, "");
        provider = Objects.requireNonNullElse(provider, "");
        license = Objects.requireNonNullElse(license, "");
        if (url.isBlank()) {
            throw new IllegalArgumentException("image URL must not be blank");
        }
    }
}
