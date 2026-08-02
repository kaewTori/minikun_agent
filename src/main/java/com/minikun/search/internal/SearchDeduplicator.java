package com.minikun.search.internal;

import com.minikun.search.model.SearchResponse;
import com.minikun.search.model.SearchResult;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class SearchDeduplicator {
    public SearchResponse deduplicate(SearchResponse response) {
        Objects.requireNonNull(response, "response must not be null");
        Set<String> seenUrls = new HashSet<>();
        Set<String> seenContent = new HashSet<>();
        var results = response.results().stream()
                .filter(result -> seenUrls.add(normalizeUrl(result.canonicalUri())))
                .filter(result -> seenContent.add(fingerprint(result)))
                .toList();
        return new SearchResponse(response.requestId(), response.status(), results, response.metadata());
    }

    private String fingerprint(SearchResult result) {
        return normalize(result.title()) + "\u0000" + normalize(result.content());
    }

    private String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String normalizeUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), null)
                    .toString().toLowerCase(Locale.ROOT);
        } catch (Exception exception) {
            return normalize(value);
        }
    }
}