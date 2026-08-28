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
        return formatImages(results, resultLimit, "");
    }

    KnowledgeContext formatImages(List<ImageSearchResult> results, int resultLimit, String query) {
        if (results == null || results.isEmpty() || resultLimit < 1) {
            return KnowledgeContext.empty();
        }
        List<ImageSource> images = new ArrayList<>();
        java.util.Set<String> seenUrls = new java.util.HashSet<>();
        for (ImageSearchResult result : rankImages(results, query)) {
            if (result == null || result.url() == null) {
                continue;
            }
            String normalizedUrl = result.url().trim();
            if (normalizedUrl.isBlank() || !isSafeImageUrl(normalizedUrl)) {
                continue;
            }
            String canonicalUrl = canonicalImageUrl(normalizedUrl);
            if (seenUrls.add(canonicalUrl)) {
                images.add(new ImageSource(
                        normalizedUrl, result.title(), result.sourceUrl(), result.description(),
                        result.thumbnailUrl(), result.width(), result.height(),
                        result.provider(), result.license()));
            }
            if (images.size() == resultLimit) {
                break;
            }
        }
        return new KnowledgeContext("", List.of(), images);
    }

    private List<ImageSearchResult> rankImages(List<ImageSearchResult> results, String query) {
        java.util.Set<String> terms = java.util.Arrays.stream(
                        java.util.Objects.requireNonNullElse(query, "").toLowerCase(java.util.Locale.ROOT)
                                .split("[^\\p{L}\\p{N}]+"))
                .filter(term -> term.length() > 1)
                .collect(java.util.stream.Collectors.toSet());
        return results.stream().filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.comparingInt((ImageSearchResult result) -> {
                    String text = (result.title() + " " + result.description()).toLowerCase(java.util.Locale.ROOT);
                    int matches = (int) terms.stream().filter(text::contains).count();
                    int metadata = (result.sourceUrl().isBlank() ? 0 : 2)
                            + (result.description().isBlank() ? 0 : 1)
                            + (result.width() == null || result.height() == null ? 0 : 1);
                    return matches * 10 + metadata;
                }).reversed())
                .toList();
    }

    private boolean isSafeImageUrl(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String canonicalImageUrl(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value).normalize();
            return new java.net.URI(uri.getScheme().toLowerCase(java.util.Locale.ROOT), uri.getUserInfo(),
                    uri.getHost().toLowerCase(java.util.Locale.ROOT), uri.getPort(), uri.getPath(),
                    uri.getQuery(), null).toASCIIString();
        } catch (java.net.URISyntaxException | IllegalArgumentException exception) {
            return value;
        }
    }
}
