package com.minikun.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BrowserContentServiceTest {
    @Test
    void extractsNormalizesDeduplicatesAndRendersUrlsInOrder() {
        AtomicInteger calls = new AtomicInteger();
        BrowserContentService service = new BrowserContentService(url -> {
            int index = calls.getAndIncrement();
            return new BrowserContent(url + "/resolved", "page " + index, "text/html; rendered", false);
        }, true, 5);

        List<String> urls = service.urlsIn("อ่าน https://example.com/a, และ https://example.com/a https://example.org/b.");
        List<com.minikun.pcs.KnowledgeCandidate> content = service.read(
                "อ่าน https://example.com/a, และ https://example.com/a https://example.org/b.");

        assertEquals(List.of("https://example.com/a", "https://example.org/b"), urls);
        assertEquals(2, content.size());
        assertEquals(2, calls.get());
        assertTrue(content.get(0).content().contains("Source URL: https://example.com/a/resolved"));
        assertTrue(content.get(1).content().contains("page 1"));
    }

    @Test
    void failsTheWholeRequestWhenAnyRenderFails() {
        BrowserContentService service = new BrowserContentService(url -> {
            throw new BrowserContentException("browser worker returned HTTP 502");
        }, true, 5);

        BrowserContentException exception = assertThrows(BrowserContentException.class,
                () -> service.read("https://example.com"));

        assertEquals("browser worker returned HTTP 502", exception.getMessage());
    }

    @Test
    void rejectsRequestsAboveTheConfiguredLimit() {
        BrowserContentService service = new BrowserContentService(
                url -> new BrowserContent(url, "content", "text/html", false), true, 1);

        assertThrows(BrowserContentException.class,
                () -> service.read("https://example.com https://example.org"));
    }
}
