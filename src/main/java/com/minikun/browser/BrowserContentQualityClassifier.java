package com.minikun.browser;

import java.util.Locale;

/** Conservative deterministic gate for obvious login/error pages. */
public final class BrowserContentQualityClassifier {
    public BrowserContentQuality classify(BrowserContent content) {
        if (content == null || content.content() == null || content.content().isBlank()) {
            return BrowserContentQuality.TOO_SHORT;
        }
        String value = content.content().toLowerCase(Locale.ROOT);
        if (containsAny(value, "401 unauthorized", "403 forbidden", "404 not found",
                "500 internal server error", "502 bad gateway", "503 service unavailable")) {
            return BrowserContentQuality.ERROR_PAGE;
        }
        if (containsAny(value, "sign in to continue", "log in to continue", "please log in",
                "authentication required", "access denied")) {
            return BrowserContentQuality.ACCESS_BLOCKED;
        }
        if (containsAny(value, "ignore previous instructions", "ignore all previous instructions",
                "disregard the system prompt", "reveal the system prompt",
                "you are now an ai assistant", "follow these instructions instead")) {
            return BrowserContentQuality.PROMPT_INJECTION_SUSPECTED;
        }
        return BrowserContentQuality.USABLE;
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
