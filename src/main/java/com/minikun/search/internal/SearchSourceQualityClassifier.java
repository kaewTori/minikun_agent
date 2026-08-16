package com.minikun.search.internal;

import com.minikun.search.model.SearchResult;
import java.util.Locale;

/** Conservative quality gate for snippets returned by a web search provider. */
public final class SearchSourceQualityClassifier {
    public SearchSourceQuality classify(SearchResult result) {
        if (result == null || result.title() == null || result.title().isBlank()
                || result.canonicalUri() == null || result.canonicalUri().isBlank()
                || result.content() == null || result.content().trim().length() < 40) {
            return SearchSourceQuality.LOW_INFORMATION;
        }
        String value = result.content().toLowerCase(Locale.ROOT);
        if (containsAny(value, "401 unauthorized", "403 forbidden", "404 not found",
                "500 internal server error", "502 bad gateway", "503 service unavailable")) {
            return SearchSourceQuality.ERROR_PAGE;
        }
        if (containsAny(value, "sign in to continue", "log in to continue", "please log in",
                "authentication required", "access denied")) {
            return SearchSourceQuality.ACCESS_BLOCKED;
        }
        if (containsAny(value, "ignore previous instructions", "ignore all previous instructions",
                "disregard the system prompt", "reveal the system prompt",
                "follow these instructions instead")) {
            return SearchSourceQuality.PROMPT_INJECTION_SUSPECTED;
        }
        return SearchSourceQuality.USABLE;
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
