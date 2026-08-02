package com.minikun.search;

import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.model.SearchResponse;

public final class SearchFormatter {
    public KnowledgeContext format(SearchResponse response) {
        if (response == null || response.results().isEmpty()) {
            return new KnowledgeContext("");
        }
        String content = response.results().stream()
                .map(result -> result.title() + " (" + result.source().canonicalUri() + "): " + result.content())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return new KnowledgeContext(content);
    }
}