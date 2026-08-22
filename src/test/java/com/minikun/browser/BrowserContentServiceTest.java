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
            return new BrowserContent(url + "/resolved",
                    "A valid rendered page with enough reference text for quality checks. page " + index,
                    "text/html; rendered", false);
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

    @Test
    void keepsSuccessfulUrlsWhenAnotherUrlFails() {
        BrowserContentService service = new BrowserContentService(url -> {
            if (url.contains("bad")) {
                throw new BrowserContentException("worker failed");
            }
            return new BrowserContent(url, "good", "text/html", false);
        }, true, 5);

        BrowserReadResult result = service.readPartial("https://good.example https://bad.example");

        assertEquals(1, result.candidates().size());
        assertEquals(List.of("https://bad.example"), result.failures().stream()
                .map(BrowserReadFailure::url).toList());
    }

    @Test
    void blocksPrivateTargetsWhenPolicyIsEnabled() {
        BrowserContentService service = new BrowserContentService(
                url -> new BrowserContent(url, "must not be called", "text/html", false),
                true, 5, null, new BrowserUrlPolicy(true));

        BrowserReadResult result = service.readPartial("http://127.0.0.1:8080/health");

        assertTrue(result.candidates().isEmpty());
        assertEquals("http://127.0.0.1:8080/health", result.failures().get(0).url());
    }

    @Test
    void readsDiscoveredUrlsWithinBothRequestedAndConfiguredBounds() {
        AtomicInteger calls = new AtomicInteger();
        BrowserContentService service = new BrowserContentService(url -> {
            calls.incrementAndGet();
            return new BrowserContent(url, "usable discovered source content", "text/html", false);
        }, true, 2);

        BrowserReadResult result = service.readUrls(List.of(
                "https://one.example", "https://two.example", "https://three.example"), 3);

        assertEquals(2, calls.get());
        assertEquals(List.of("https://one.example", "https://two.example"),
                result.candidates().stream().map(com.minikun.pcs.KnowledgeCandidate::provenance).toList());
    }
}
