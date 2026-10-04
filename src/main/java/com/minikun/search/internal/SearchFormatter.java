package com.minikun.search.internal;

import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.pcs.model.ImageSource;
import com.minikun.search.model.ImageSearchResult;
import com.minikun.search.model.SearchResponse;
import com.minikun.search.model.SearchResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

final class SearchFormatter {
    private static final int MAX_IMAGES_PER_SOURCE = 2;
    private static final String IMAGE_CANDIDATE_METRIC = "minikun.search.images.candidates";
    private static final String IMAGE_ACCEPTED_METRIC = "minikun.search.images.accepted";
    private static final String IMAGE_REJECTED_METRIC = "minikun.search.images.rejected";
    private static final Set<String> IMAGE_QUERY_NOISE = Set.of(
            "a", "an", "about", "find", "for", "give", "image", "images", "look", "me", "of",
            "photo", "photos", "picture", "pictures", "show", "some", "the", "to", "want",
            "art", "artwork", "artworks", "by", "drawing", "drawings", "hd", "high", "illustration",
            "illustrations", "official", "portrait", "portraits", "quality", "render", "renders",
            "stock", "wallpaper", "wallpapers", "งานวาด", "ผลงาน", "รูป", "รูปภาพ", "ภาพ");
    private final SearchSourceQualityClassifier qualityClassifier = new SearchSourceQualityClassifier();
    private final MeterRegistry meterRegistry;

    SearchFormatter() {
        this(null);
    }

    SearchFormatter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

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
                result.source().canonicalUri(), result.publishedAt(), result.providerScore()));
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
        return formatImages(results, resultLimit, query, false);
    }

    private KnowledgeContext formatImages(
            List<ImageSearchResult> results,
            int resultLimit,
            String query,
            boolean preserveProviderOrder) {
        if (results == null || results.isEmpty() || resultLimit < 1) {
            return KnowledgeContext.empty();
        }
        List<String> terms = imageTerms(query);
        boolean requireRelevance = query != null && !query.isBlank();
        List<ImageSearchResult> ranked = preserveProviderOrder
                ? results.stream().filter(Objects::nonNull).toList()
                : rankImages(results, query);
        for (ImageSearchResult result : ranked) {
            if (result != null) {
                recordImageMetric(IMAGE_CANDIDATE_METRIC, result, imageDomain(result), "");
            }
        }
        List<ImageSource> images = new ArrayList<>();
        Set<String> seenImageKeys = new HashSet<>();
        Map<String, Integer> sourceCounts = new HashMap<>();
        for (ImageSearchResult result : ranked) {
            if (result == null) {
                continue;
            }
            if (requireRelevance && terms.isEmpty()) {
                recordImageMetric(IMAGE_REJECTED_METRIC, result, imageDomain(result), "missing_anchor");
                continue;
            }
            if (requireRelevance && imageRelevanceScore(result, terms) == 0) {
                recordImageMetric(IMAGE_REJECTED_METRIC, result, imageDomain(result), "unrelated");
                continue;
            }
            String normalizedUrl = result.url().trim();
            if (normalizedUrl.isBlank()) {
                recordImageMetric(IMAGE_REJECTED_METRIC, result, imageDomain(result), "blank_url");
                continue;
            }
            if (!isSafeImageUrl(normalizedUrl)) {
                recordImageMetric(IMAGE_REJECTED_METRIC, result, imageDomain(result), "unsafe_url");
                continue;
            }
            String urlKey = "url:" + canonicalImageUrl(normalizedUrl);
            String pageKey = imagePageKey(result);
            if (!seenImageKeys.add(urlKey) || (!pageKey.isBlank() && !seenImageKeys.add(pageKey))) {
                recordImageMetric(IMAGE_REJECTED_METRIC, result, imageDomain(result), "duplicate");
                continue;
            }
            String sourceKey = imageSourceKey(result);
            if (sourceCounts.getOrDefault(sourceKey, 0) >= MAX_IMAGES_PER_SOURCE) {
                recordImageMetric(IMAGE_REJECTED_METRIC, result, imageDomain(result), "source_limit");
                continue;
            }
            images.add(new ImageSource(
                    normalizedUrl, result.title(), result.sourceUrl(), result.description(),
                    result.thumbnailUrl(), result.width(), result.height(),
                    result.provider(), result.license()));
            sourceCounts.merge(sourceKey, 1, Integer::sum);
            recordImageMetric(IMAGE_ACCEPTED_METRIC, result, imageDomain(result), "");
            if (images.size() == resultLimit) {
                break;
            }
        }
        return new KnowledgeContext("", List.of(), images);
    }

    private List<ImageSearchResult> rankImages(List<ImageSearchResult> results, String query) {
        List<String> terms = imageTerms(query);
        return results.stream().filter(Objects::nonNull)
                .sorted(java.util.Comparator.comparingInt((ImageSearchResult result) -> {
                    int matches = imageRelevanceScore(result, terms);
                    int metadata = (result.sourceUrl().isBlank() ? 0 : 2)
                            + (result.description().isBlank() ? 0 : 1)
                            + (result.width() == null || result.height() == null ? 0 : 1);
                    return matches + metadata;
                }).reversed())
                .toList();
    }

    private List<String> imageTerms(String query) {
        return SearchTermTokenizer.tokenize(DefaultSearchQueryPlanningService.focusImageQuery(query)).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !IMAGE_QUERY_NOISE.contains(value))
                .toList();
    }

    // ponytail: lexical metadata gate; add a vision reranker only if measured false negatives justify its latency.
    private int imageRelevanceScore(ImageSearchResult result, List<String> terms) {
        String title = result.title().toLowerCase(Locale.ROOT);
        String description = result.description().toLowerCase(Locale.ROOT);
        String sourceUrl = result.sourceUrl().toLowerCase(Locale.ROOT);
        String metadata = title + " " + description;
        if (terms.isEmpty() || !containsImageTerm(metadata, terms.getFirst())) {
            return 0;
        }
        int matches = (int) terms.stream().filter(term -> containsImageTerm(metadata, term)).count();
        int titleMatches = (int) terms.stream().filter(term -> containsImageTerm(title, term)).count();
        int sourceMatches = (int) terms.stream().filter(term -> containsImageTerm(sourceUrl, term)).count();
        return 50 + matches * 100 + titleMatches * 10 + sourceMatches * 5;
    }

    private boolean containsImageTerm(String text, String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        if (term.codePoints().anyMatch(value -> value >= 0x0E00 && value <= 0x0E7F)) {
            return text.contains(term.toLowerCase(Locale.ROOT));
        }
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(term)
                        + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(text)
                .find();
    }

    private String imagePageKey(ImageSearchResult result) {
        if (result.sourceUrl().isBlank() || result.title().isBlank()) {
            return "";
        }
        return "page:" + canonicalImageUrl(result.sourceUrl()) + "|title:"
                + result.title().trim().toLowerCase(Locale.ROOT);
    }

    private String imageSourceKey(ImageSearchResult result) {
        String sourceHost = host(result.sourceUrl());
        if (!sourceHost.isBlank()) {
            return sourceHost;
        }
        if (!result.sourceUrl().isBlank()) {
            return result.sourceUrl().trim().toLowerCase(Locale.ROOT);
        }
        String imageHost = host(result.url());
        return imageHost.isBlank() ? "unknown" : imageHost;
    }

    private String imageDomain(ImageSearchResult result) {
        String sourceHost = host(result.sourceUrl());
        if (!sourceHost.isBlank()) {
            return sourceHost;
        }
        String imageHost = host(result.url());
        return imageHost.isBlank() ? "unknown" : imageHost;
    }

    private String host(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            String host = URI.create(value.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private void recordImageMetric(String name, ImageSearchResult result, String domain, String reason) {
        if (meterRegistry == null) {
            return;
        }
        try {
            Counter.Builder builder = Counter.builder(name)
                    .tag("provider", metricTag(result.provider()))
                    .tag("domain", metricTag(domain));
            if (!reason.isBlank()) {
                builder.tag("reason", reason);
            }
            builder.register(meterRegistry).increment();
        } catch (RuntimeException ignored) {
            // Observability must not affect image selection.
        }
    }

    private String metricTag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
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
            if (uri.getScheme() == null || uri.getHost() == null) {
                return value.trim().toLowerCase(Locale.ROOT);
            }
            return new java.net.URI(uri.getScheme().toLowerCase(Locale.ROOT), uri.getUserInfo(),
                    uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), uri.getPath(),
                    null, null).toASCIIString();
        } catch (java.net.URISyntaxException | IllegalArgumentException exception) {
            return value;
        }
    }
}
