package com.minikun.search.model;

import java.util.Objects;

/** Provider-neutral options that can influence search results. */
public record SearchOptions(
        String language,
        String category,
        String timeRange,
        boolean safeSearch) {
    public static final String IMAGE_CATEGORY = "images";

    public SearchOptions {
        language = normalize(language);
        category = normalize(category);
        timeRange = normalize(timeRange);
    }

    public static SearchOptions defaults() {
        return new SearchOptions("", "", "", false);
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
