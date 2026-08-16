package com.minikun.browser;

public record BrowserReadFailure(String url, String reason) {
    public BrowserReadFailure {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        reason = reason == null || reason.isBlank() ? "unknown browser failure" : reason;
    }
}
