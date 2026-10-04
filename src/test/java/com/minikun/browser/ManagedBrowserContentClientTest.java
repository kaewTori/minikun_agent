package com.minikun.browser;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ManagedBrowserContentClientTest {
    @Test
    void sharesCacheAcrossConcurrentRequestsAndInvalidatesAfterManualVerification() {
        AtomicInteger calls = new AtomicInteger();
        var client = new ManagedBrowserContentClient(url -> {
            calls.incrementAndGet();
            return new BrowserContent(url, "Article content", "text/markdown", false);
        }, Duration.ofMinutes(5), Duration.ZERO, 2, 3);
        var first = CompletableFuture.supplyAsync(() -> client.render("https://example.com/a"));
        var second = CompletableFuture.supplyAsync(() -> client.render("https://example.com/a#section"));
        first.join(); second.join();
        assertEquals(1, calls.get());
        client.invalidateHost("https://example.com/a");
        client.render("https://example.com/a");
        assertEquals(2, calls.get());
    }

    @Test
    void boundsCacheAndExpiresEntries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var client = new ManagedBrowserContentClient(url -> {
            calls.incrementAndGet(); return new BrowserContent(url, "content", "text/plain", false);
        }, Duration.ofMillis(30), Duration.ZERO, 1, 1);
        client.render("https://example.com/a");
        client.render("https://example.com/b");
        client.render("https://example.com/a");
        assertEquals(3, calls.get());
        Thread.sleep(40);
        client.render("https://example.com/a");
        assertEquals(4, calls.get());
    }

    @Test
    void pacesDifferentUrlsOnSameHostAcrossRequests() {
        var client = new ManagedBrowserContentClient(url -> new BrowserContent(url, "content", "text/plain", false),
                Duration.ZERO, Duration.ofMillis(50), 1, 2);
        client.render("https://example.com/a");
        long started = System.nanoTime();
        client.render("https://example.com/b");
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 45);
    }

    @Test
    void neverCachesChallengePagesOrFailures() {
        AtomicInteger calls = new AtomicInteger();
        var client = new ManagedBrowserContentClient(url -> {
            calls.incrementAndGet(); return new BrowserContent(url, "Verify you are human", "text/plain", false);
        }, Duration.ofMinutes(5), Duration.ZERO, 1, 1);
        client.render("https://example.com/a"); client.render("https://example.com/a");
        assertEquals(2, calls.get());
    }
}
