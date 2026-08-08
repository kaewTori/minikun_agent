package com.minikun.search.internal;

import com.minikun.search.model.SearchResponse;
import com.minikun.search.model.SearchResult;
import com.minikun.search.model.SearchOptions;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Lightweight lexical/freshness ranking that is deterministic and provider-neutral. */
public final class SearchRanker {
    public SearchResponse rank(SearchResponse response, String query, SearchOptions options) {
        if (response == null || response.results().size() < 2) {
            return response;
        }
        List<String> terms = terms(query);
        Map<SearchResult, Double> scores = new HashMap<>();
        for (SearchResult result : response.results()) {
            scores.put(result, score(result, query, terms, options));
        }
        List<SearchResult> ranked = new ArrayList<>(response.results());
        ranked.sort(Comparator.comparingDouble((SearchResult result) -> scores.get(result)).reversed()
                .thenComparing(result -> result.sourcePosition() == null ? Integer.MAX_VALUE : result.sourcePosition()));
        return new SearchResponse(response.requestId(), response.status(), diversify(ranked, scores), response.metadata());
    }

    private List<SearchResult> diversify(List<SearchResult> ranked, Map<SearchResult, Double> scores) {
        List<SearchResult> result = new ArrayList<>();
        Set<String> domains = new HashSet<>();
        List<SearchResult> deferred = new ArrayList<>();
        for (SearchResult item : ranked) {
            String domain = domain(item.canonicalUri());
            if (domains.add(domain) || result.size() < 2) {
                result.add(item);
            } else {
                deferred.add(item);
            }
        }
        deferred.sort(Comparator.comparingDouble((SearchResult item) -> scores.get(item)).reversed());
        result.addAll(deferred);
        return result;
    }

    private double score(SearchResult result, String query, List<String> terms, SearchOptions options) {
        String title = result.title().toLowerCase(Locale.ROOT);
        String content = result.content().toLowerCase(Locale.ROOT);
        String normalizedQuery = query.toLowerCase(Locale.ROOT).trim();
        long matched = terms.stream().filter(term -> title.contains(term) || content.contains(term)).count();
        double score = terms.isEmpty() ? 0.0 : (double) matched / terms.size();
        if (!normalizedQuery.isBlank() && title.contains(normalizedQuery)) {
            score += 0.75;
        }
        if ("day".equals(options.timeRange()) || "week".equals(options.timeRange())) {
            long ageHours = Math.max(0, Duration.between(result.source().retrievedAt(), Instant.now()).toHours());
            score += Math.max(0.0, 0.25 - ageHours / 1000.0);
        }
        return score;
    }

    private List<String> terms(String query) {
        List<String> result = new ArrayList<>();
        for (String term : query.toLowerCase(Locale.ROOT).split("\\s+")) {
            String clean = term.replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
            if (clean.length() >= 2 && !Set.of("the", "and", "for", "ของ", "ที่", "ให้").contains(clean)) {
                result.add(clean);
            }
        }
        return result;
    }

    private String domain(String value) {
        try {
            return URI.create(value).getHost();
        } catch (RuntimeException exception) {
            return value;
        }
    }
}
