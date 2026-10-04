package com.minikun.browser;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded process-local cache and shared per-host pacing, including concurrent chat requests. */
public final class ManagedBrowserContentClient implements BrowserContentClient {
    private final BrowserContentClient delegate;
    private final Duration ttl;
    private final Duration interval;
    private final int capacity;
    private final Semaphore permits;
    // ponytail: 64 lock stripes can serialize unrelated hosts; use a bounded host-lock pool if measured contention matters.
    private final ReentrantLock[] locks = new ReentrantLock[64];
    private final long[] nextRead = new long[64];
    private final Map<String, Cached> cache = new LinkedHashMap<>(16, .75f, true);
    private long generation;
    private final BrowserContentQualityClassifier quality = new BrowserContentQualityClassifier();

    public ManagedBrowserContentClient(BrowserContentClient delegate, Duration ttl, Duration interval,
            int capacity, int concurrency) {
        if (ttl.isNegative() || interval.isNegative() || capacity < 1 || concurrency < 1) {
            throw new IllegalArgumentException("invalid browser cache or pacing settings");
        }
        this.delegate = delegate;
        this.ttl = ttl;
        this.interval = interval;
        this.capacity = capacity;
        this.permits = new Semaphore(concurrency, true);
        for (int i = 0; i < locks.length; i++) locks[i] = new ReentrantLock(true);
    }

    @Override
    public BrowserContent render(String url) {
        URI uri = URI.create(url);
        String key = url.contains("#") ? url.substring(0, url.indexOf('#')) : url;
        int stripe = Math.floorMod(uri.getHost().toLowerCase(java.util.Locale.ROOT).hashCode(), locks.length);
        boolean locked = false;
        boolean acquired = false;
        long cacheGeneration;
        try {
            locks[stripe].lockInterruptibly();
            locked = true;
            synchronized (cache) {
                cacheGeneration = generation;
                Cached found = cache.get(key);
                if (found != null && System.nanoTime() - found.expiresAt() < 0) return found.content();
                cache.remove(key);
            }
            long wait = nextRead[stripe] - System.nanoTime();
            if (wait > 0) Crawl4AiBrowserContentClient.pause(Duration.ofNanos(wait).plusMillis(1));
            permits.acquire();
            acquired = true;
            BrowserContent result;
            try {
                result = delegate.render(url);
            } finally {
                nextRead[stripe] = System.nanoTime() + interval.toNanos();
            }
            if (result != null && quality.classify(result) == BrowserContentQuality.USABLE && !ttl.isZero()) {
                synchronized (cache) {
                    if (generation == cacheGeneration) cache.put(key, new Cached(result, System.nanoTime() + ttl.toNanos()));
                    while (cache.size() > capacity) cache.remove(cache.keySet().iterator().next());
                }
            }
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BrowserContentException("browser read interrupted", exception);
        } finally {
            if (acquired) permits.release();
            if (locked) locks[stripe].unlock();
        }
    }

    public void invalidateHost(String url) {
        String host = URI.create(url).getHost();
        int stripe = Math.floorMod(host.toLowerCase(java.util.Locale.ROOT).hashCode(), locks.length);
        locks[stripe].lock();
        try {
            synchronized (cache) {
                cache.keySet().removeIf(key -> host.equalsIgnoreCase(URI.create(key).getHost()));
            }
        } finally {
            locks[stripe].unlock();
        }
    }

    public void clear() {
        synchronized (cache) { generation++; cache.clear(); }
    }

    private record Cached(BrowserContent content, long expiresAt) { }
}
