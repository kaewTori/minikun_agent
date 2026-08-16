package com.minikun.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BrowserContentQualityClassifierTest {
    private final BrowserContentQualityClassifier classifier = new BrowserContentQualityClassifier();

    @Test
    void rejectsObviousLoginAndErrorPages() {
        assertEquals(BrowserContentQuality.ACCESS_BLOCKED,
                classifier.classify(new BrowserContent("https://example.com", "Please log in to continue", "text/html", false)));
        assertEquals(BrowserContentQuality.ERROR_PAGE,
                classifier.classify(new BrowserContent("https://example.com", "503 Service Unavailable", "text/html", false)));
    }
}
