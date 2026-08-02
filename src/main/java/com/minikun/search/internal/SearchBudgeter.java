package com.minikun.search.internal;

import com.minikun.search.model.SearchResponse;
import com.minikun.search.model.SearchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SearchBudgeter {
    private final int maximumCharacters;

    public SearchBudgeter(int maximumCharacters) {
        if (maximumCharacters < 1) {
            throw new IllegalArgumentException("search maximum characters must be positive");
        }
        this.maximumCharacters = maximumCharacters;
    }

    public SearchResponse budget(SearchResponse response) {
        Objects.requireNonNull(response, "response must not be null");
        List<SearchResult> budgeted = new ArrayList<>();
        int used = 0;
        for (int index = 0; index < response.results().size(); index++) {
            SearchResult result = response.results().get(index);
            int separator = budgeted.isEmpty() ? 0 : 1;
            int overhead = formattedOverhead(result);
            int reservedForRemaining = minimumRemaining(response.results(), index + 1)
                    + Math.max(0, response.results().size() - index - 1);
            int availableContent = maximumCharacters - used - separator - overhead - reservedForRemaining;
            if (availableContent < 1) {
                break;
            }
            String content = result.content().substring(0,
                    Math.min(result.content().length(), availableContent));
            SearchResult bounded = content.equals(result.content())
                    ? result
                    : new SearchResult(result.title(), result.canonicalUri(), content,
                            result.source(), result.sourcePosition());
            budgeted.add(bounded);
            used += separator + formattedOverhead(bounded) + content.length();
        }
        return new SearchResponse(response.requestId(), response.status(), List.copyOf(budgeted), response.metadata());
    }

    private int minimumRemaining(List<SearchResult> results, int fromIndex) {
        return results.subList(fromIndex, results.size()).stream()
                .mapToInt(result -> formattedOverhead(result) + 1)
                .sum();
    }

    private int formattedOverhead(SearchResult result) {
        return result.title().length() + result.source().canonicalUri().length() + 4;
    }
}