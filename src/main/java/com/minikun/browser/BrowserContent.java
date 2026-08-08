package com.minikun.browser;

import java.util.Objects;

/** Rendered browser content returned by minikun-browser-worker. */
public record BrowserContent(String url, String content, String contentType, boolean truncated) {
    public BrowserContent {
        Objects.requireNonNull(url, "url must not be null");
        Objects.requireNonNull(content, "content must not be null");
        contentType = Objects.requireNonNullElse(contentType, "");
    }
}
