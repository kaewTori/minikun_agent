package com.minikun.browser;

import java.util.Objects;

/** Rendered browser content returned by Crawl4AI. */
public record BrowserContent(String url, String content, String contentType, boolean truncated, String rawContent) {
    public BrowserContent(String url, String content, String contentType, boolean truncated) {
        this(url, content, contentType, truncated, content);
    }

    public BrowserContent {
        Objects.requireNonNull(url, "url must not be null");
        Objects.requireNonNull(content, "content must not be null");
        contentType = Objects.requireNonNullElse(contentType, "");
        rawContent = Objects.requireNonNullElse(rawContent, content);
    }
}
