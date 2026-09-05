package com.minikun.search.internal;

import com.minikun.search.SearchQueryRewriteService;
import com.minikun.search.model.SearchQuery;
import java.util.Objects;
import java.util.regex.Pattern;

public final class DefaultSearchQueryRewriteService implements SearchQueryRewriteService {
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]");
    private static final Pattern FULL_WIDTH_ASCII = Pattern.compile("[\\uFF01-\\uFF5E]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\p{javaWhitespace}\\p{Z}]+");

    @Override
    public SearchQuery rewrite(String query) {
        Objects.requireNonNull(query, "query must not be null");
        String normalized = ZERO_WIDTH.matcher(query).replaceAll("");
        normalized = FULL_WIDTH_ASCII.matcher(normalized).replaceAll(match ->
                Character.toString(match.group().charAt(0) - 0xFEE0));
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ").strip();
        return new SearchQuery(query, normalized);
    }
}
