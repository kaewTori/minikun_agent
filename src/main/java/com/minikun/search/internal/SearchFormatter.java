package com.minikun.search.internal;

import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.pcs.model.ImageSource;
import com.minikun.search.model.ImageSearchResult;
import com.minikun.search.model.SearchResponse;
import com.minikun.search.model.SearchResult;

import java.util.ArrayList;
import java.util.List;

final class SearchFormatter {
    private final SearchSourceQualityClassifier qualityClassifier = new SearchSourceQualityClassifier();

    KnowledgeContext format(SearchResponse response) {
        return format(response, "");
    }

    KnowledgeContext format(SearchResponse response, String query) {
        if (response == null || response.results().isEmpty()) {
            return new KnowledgeContext("");
        }
        List<KnowledgeCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < response.results().size(); index++) {
            var result = response.results().get(index);
            SearchSourceQuality quality = qualityClassifier.classify(result);
            if (quality == SearchSourceQuality.ERROR_PAGE
                    || quality == SearchSourceQuality.ACCESS_BLOCKED
                    || quality == SearchSourceQuality.PROMPT_INJECTION_SUSPECTED) {
                continue;
            }
            String content = result.title() + " (" + result.source().canonicalUri() + "): " + result.content();
            candidates.add(new KnowledgeCandidate(
                "search-" + index, KnowledgeSource.SEARCH, content, index,
                result.source().canonicalUri()));
        }
        return KnowledgeContext.fromCandidates(candidates);
    }

    SearchResponse filterQuality(SearchResponse response) {
        if (response == null || response.results().isEmpty()) {
            return response;
        }
        List<SearchResult> usable = response.results().stream()
                .filter(result -> {
                    SearchSourceQuality quality = qualityClassifier.classify(result);
                    return quality == SearchSourceQuality.USABLE
                            || quality == SearchSourceQuality.LOW_INFORMATION;
                })
                .toList();
        return usable.size() == response.results().size()
                ? response
                : new SearchResponse(response.requestId(), response.status(), usable, response.metadata());
    }

    KnowledgeContext formatImages(List<ImageSearchResult> results, int resultLimit) {
        if (results == null || results.isEmpty() || resultLimit < 1) {
            return KnowledgeContext.empty();
        }
        List<ImageSource> images = new ArrayList<>();
        java.util.Set<String> seenUrls = new java.util.HashSet<>();
        for (ImageSearchResult result : results) {
            if (result == null || result.url() == null) {
                continue;
            }
            String normalizedUrl = result.url().trim();
            if (normalizedUrl.isBlank()) {
                continue;
            }
            if (seenUrls.add(normalizedUrl)) {
                images.add(new ImageSource(
                        normalizedUrl, result.title(), result.sourceUrl(), result.description()));
            }
            if (images.size() == resultLimit) {
                break;
            }
        }
        return new KnowledgeContext("", List.of(), images);
    }
}
