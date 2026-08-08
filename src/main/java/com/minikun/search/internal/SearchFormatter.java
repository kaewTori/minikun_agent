package com.minikun.search.internal;

import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.search.model.SearchResponse;

import java.util.ArrayList;
import java.util.List;

final class SearchFormatter {
    KnowledgeContext format(SearchResponse response) {
        if (response == null || response.results().isEmpty()) {
            return new KnowledgeContext("");
        }
        List<KnowledgeCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < response.results().size(); index++) {
            var result = response.results().get(index);
            String content = result.title() + " (" + result.source().canonicalUri() + "): " + result.content();
            candidates.add(new KnowledgeCandidate(
                "search-" + index, KnowledgeSource.SEARCH, content, index));
        }
        return KnowledgeContext.fromCandidates(candidates);
    }
}