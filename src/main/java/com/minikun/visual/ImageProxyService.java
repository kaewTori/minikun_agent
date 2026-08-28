package com.minikun.visual;

import com.minikun.browser.BrowserContentException;
import com.minikun.browser.BrowserUrlPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Downloads untrusted search images through a bounded SSRF-safe cache. */
public final class ImageProxyService {
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private final HttpClient httpClient;
    private final BrowserUrlPolicy urlPolicy = new BrowserUrlPolicy(true);
    private final Duration readTimeout;
    private final Duration cacheTtl;
    private final int maxBytes;
    private final int maxEntries;
    private final Clock clock;
    private final Map<String, CachedImage> cache;

    public ImageProxyService(Duration connectTimeout, Duration readTimeout, Duration cacheTtl,
            int maxBytes, int maxEntries, Clock clock) {
        if (maxBytes < 1 || maxEntries < 1) throw new IllegalArgumentException("image proxy limits must be positive");
        this.readTimeout = positive(readTimeout, Duration.ofSeconds(15));
        this.cacheTtl = positive(cacheTtl, Duration.ofHours(6));
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
        this.clock = clock;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(positive(connectTimeout, Duration.ofSeconds(5)))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.cache = new LinkedHashMap<>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, CachedImage> eldest) {
                return size() > ImageProxyService.this.maxEntries;
            }
        };
    }

    public CachedImage fetch(String source) {
        URI uri = validate(source);
        String key = uri.normalize().toASCIIString();
        synchronized (cache) {
            CachedImage value = cache.get(key);
            if (value != null && value.cachedAt().plus(cacheTtl).isAfter(clock.instant())) return value;
            cache.remove(key);
        }
        CachedImage downloaded = download(uri);
        synchronized (cache) { cache.put(key, downloaded); }
        return downloaded;
    }

    private URI validate(String source) {
        try {
            URI uri = URI.create(source == null ? "" : source.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new ImageProxyException("image URL must use HTTPS");
            }
            urlPolicy.validate(uri);
            return uri;
        } catch (IllegalArgumentException | BrowserContentException exception) {
            throw new ImageProxyException("image URL is not allowed", exception);
        }
    }

    private CachedImage download(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(readTimeout)
                .header("Accept", "image/avif,image/webp,image/png,image/jpeg;q=0.9")
                .header("User-Agent", "Minikun-ImageProxy/1.0").GET().build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream ignored = response.body()) { /* close response */ }
                throw new ImageProxyException("image host returned HTTP " + response.statusCode());
            }
            String type = response.headers().firstValue("Content-Type").orElse("")
                    .split(";", 2)[0].trim().toLowerCase();
            if (!ALLOWED_TYPES.contains(type)) {
                try (InputStream ignored = response.body()) { /* close response */ }
                throw new ImageProxyException("unsupported image content type");
            }
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (length > maxBytes) {
                try (InputStream ignored = response.body()) { /* close response */ }
                throw new ImageProxyException("image is larger than the configured limit");
            }
            byte[] bytes;
            try (InputStream input = response.body()) { bytes = input.readNBytes(maxBytes + 1); }
            if (bytes.length == 0 || bytes.length > maxBytes) throw new ImageProxyException("invalid image size");
            return new CachedImage(bytes, type, sha256(bytes), clock.instant());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ImageProxyException("image download was interrupted", exception);
        } catch (IOException exception) {
            throw new ImageProxyException("image download failed", exception);
        }
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }

    private Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    public record CachedImage(byte[] bytes, String contentType, String etag, Instant cachedAt) {
        public CachedImage { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
