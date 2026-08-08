package com.minikun.search;

import java.util.Objects;
import com.minikun.search.model.SearchOptions;

public record SearchCacheKey(String normalizedQuery, int maximumResultCount, String optionsFingerprint) {
    public SearchCacheKey {
        Objects.requireNonNull(normalizedQuery, "normalized query must not be null");
        if (normalizedQuery.isBlank()) {
            throw new IllegalArgumentException("normalized query must not be blank");
        }
        if (maximumResultCount < 1) {
            throw new IllegalArgumentException("maximum result count must be positive");
        }
        optionsFingerprint = Objects.requireNonNullElse(optionsFingerprint, "default");
    }

    public static SearchCacheKey from(String query, int maximumResultCount) {
        return from(query, maximumResultCount, SearchOptions.defaults());
    }

    public static SearchCacheKey from(String query, int maximumResultCount, SearchOptions options) {
        return from(query, maximumResultCount, options, "v1");
    }

    public static SearchCacheKey from(String query, int maximumResultCount,
            SearchOptions options, String providerVersion) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(options, "options must not be null");
        String normalized = query.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        String fingerprint = String.join("|", Objects.requireNonNullElse(providerVersion, "v1"),
                options.language(), options.category(), options.timeRange(), Boolean.toString(options.safeSearch()));
        return new SearchCacheKey(normalized, maximumResultCount, fingerprint);
    }
}
