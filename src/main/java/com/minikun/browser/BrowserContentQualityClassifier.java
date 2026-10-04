package com.minikun.browser;

import java.util.Locale;

/** Conservative deterministic gate for obvious login/error pages. */
public final class BrowserContentQualityClassifier {
    public BrowserContentQuality classify(BrowserContent content) {
        if (content == null || content.content() == null || content.content().isBlank()) {
            return BrowserContentQuality.TOO_SHORT;
        }
        String value = content.content().toLowerCase(Locale.ROOT);
        if (isChallenge(value) || isChallenge(content.rawContent())) {
            return BrowserContentQuality.CHALLENGE_REQUIRED;
        }
        value += "\n" + content.rawContent().toLowerCase(Locale.ROOT);
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

    public boolean isChallenge(String value) {
        value = value == null ? "" : value.toLowerCase(Locale.ROOT);
        // ponytail: challenge signatures are heuristic; add observed variants instead of blocking mentions of Cloudflare.
        return value.contains("<title>just a moment")
                || value.contains("cf-chl-") && containsAny(value, "challenge-platform", "challenge-form")
                || value.strip().matches("(?s)^(?:#\\s*)?just a moment(?:\\.{0,3}|…)?\\s*$")
                || value.length() < 4_000 && value.strip().startsWith("just a moment") && value.contains("cloudflare")
                || value.length() < 4_000 && containsAny(value,
                        "verify you are human", "verifying you are human", "checking your browser before accessing",
                        "performing security verification", "enable javascript and cookies to continue",
                        "complete the captcha", "ยืนยันว่าคุณเป็นมนุษย์");
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
