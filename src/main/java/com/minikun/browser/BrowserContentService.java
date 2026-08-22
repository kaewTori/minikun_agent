package com.minikun.browser;

import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BrowserContentService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrowserContentService.class);
    private static final Pattern URL_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://[^\\s<>\\\"]+");
    private final BrowserContentClient client;
    private final boolean enabled;
    private final int maxUrls;
    private final MeterRegistry meterRegistry;
    private final BrowserUrlPolicy urlPolicy;
    private final int maxContentCharacters;
    private final BrowserContentQualityClassifier qualityClassifier;
    private final int maxConcurrentUrls;

    public BrowserContentService(BrowserContentClient client, boolean enabled, int maxUrls) {
        this(client, enabled, maxUrls, null, BrowserUrlPolicy.permissive());
    }

    public BrowserContentService(
            BrowserContentClient client, boolean enabled, int maxUrls, MeterRegistry meterRegistry) {
        this(client, enabled, maxUrls, meterRegistry, BrowserUrlPolicy.permissive());
    }

    public BrowserContentService(
            BrowserContentClient client, boolean enabled, int maxUrls,
            MeterRegistry meterRegistry, BrowserUrlPolicy urlPolicy) {
        this(client, enabled, maxUrls, meterRegistry, urlPolicy, 12_000);
    }

    public BrowserContentService(
            BrowserContentClient client, boolean enabled, int maxUrls,
            MeterRegistry meterRegistry, BrowserUrlPolicy urlPolicy,
            int maxContentCharacters) {
        this(client, enabled, maxUrls, meterRegistry, urlPolicy, maxContentCharacters, 1);
    }

    public BrowserContentService(
            BrowserContentClient client, boolean enabled, int maxUrls,
            MeterRegistry meterRegistry, BrowserUrlPolicy urlPolicy,
            int maxContentCharacters, int maxConcurrentUrls) {
        this.client = client;
        this.enabled = enabled;
        this.maxUrls = maxUrls;
        this.meterRegistry = meterRegistry;
        this.urlPolicy = urlPolicy == null ? BrowserUrlPolicy.permissive() : urlPolicy;
        if (maxContentCharacters < 1) {
            throw new IllegalArgumentException("max browser content characters must be positive");
        }
        this.maxContentCharacters = maxContentCharacters;
        this.qualityClassifier = new BrowserContentQualityClassifier();
        if (maxConcurrentUrls < 1) {
            throw new IllegalArgumentException("max concurrent browser URLs must be positive");
        }
        this.maxConcurrentUrls = maxConcurrentUrls;
    }

    public List<KnowledgeCandidate> read(String message) {
        BrowserReadResult result = readPartial(message);
        if (!result.failures().isEmpty()) {
            BrowserReadFailure failure = result.failures().get(0);
            throw new BrowserContentException(failure.reason());
        }
        return result.candidates();
    }

    public BrowserReadResult readPartial(String message) {
        List<String> urls = urlsIn(message);
        if (urls.isEmpty() || !enabled) {
            return new BrowserReadResult(List.of(), List.of());
        }
        if (urls.size() > maxUrls) {
            throw new BrowserContentException("too many links (maximum " + maxUrls + ")");
        }
        Semaphore permits = new Semaphore(maxConcurrentUrls);
        List<CompletableFuture<UrlReadResult>> futures = new ArrayList<>();
        for (int index = 0; index < urls.size(); index++) {
            final int urlIndex = index;
            final String requestedUrl = urls.get(index);
            futures.add(CompletableFuture.supplyAsync(() -> {
                boolean acquired = false;
                try {
                    permits.acquire();
                    acquired = true;
                    return readOne(urlIndex, requestedUrl);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return new UrlReadResult(null,
                            new BrowserReadFailure(requestedUrl, "browser read interrupted"));
                } finally {
                    if (acquired) {
                        permits.release();
                    }
                }
            }));
        }
        List<KnowledgeCandidate> candidates = new ArrayList<>();
        List<BrowserReadFailure> failures = new ArrayList<>();
        for (CompletableFuture<UrlReadResult> future : futures) {
            try {
                UrlReadResult result = future.join();
                if (result.candidate() != null) {
                    candidates.add(result.candidate());
                }
                if (result.failure() != null) {
                    failures.add(result.failure());
                }
            } catch (CompletionException exception) {
                failures.add(new BrowserReadFailure("unknown", "browser worker failed"));
            }
        }
        return new BrowserReadResult(candidates, failures);
    }

    /** Reads a bounded set of URLs discovered by another trusted pipeline stage. */
    public BrowserReadResult readUrls(List<String> urls, int requestedLimit) {
        if (urls == null || urls.isEmpty() || requestedLimit < 1) {
            return new BrowserReadResult(List.of(), List.of());
        }
        List<String> bounded = urls.stream()
                .filter(url -> url != null && !url.isBlank())
                .map(String::trim)
                .distinct()
                .limit(Math.min(maxUrls, requestedLimit))
                .toList();
        return bounded.isEmpty()
                ? new BrowserReadResult(List.of(), List.of())
                : readPartial(String.join("\n", bounded));
    }

    private UrlReadResult readOne(int index, String requestedUrl) {
        try {
            urlPolicy.validate(new URI(requestedUrl));
        } catch (BrowserContentException exception) {
            recordOutcome("blocked", System.nanoTime());
            return new UrlReadResult(null, new BrowserReadFailure(requestedUrl, exception.getMessage()));
        } catch (URISyntaxException exception) {
            return new UrlReadResult(null, new BrowserReadFailure(requestedUrl, "invalid URL"));
        }
        long started = System.nanoTime();
        try {
            BrowserContent rendered = client.render(requestedUrl);
            if (rendered == null || rendered.content().isBlank()) {
                recordOutcome("failure", started);
                return new UrlReadResult(null,
                        new BrowserReadFailure(requestedUrl, "browser worker returned empty content"));
            }
            BrowserContentQuality quality = qualityClassifier.classify(rendered);
            if (quality == BrowserContentQuality.ERROR_PAGE
                    || quality == BrowserContentQuality.ACCESS_BLOCKED
                    || quality == BrowserContentQuality.PROMPT_INJECTION_SUSPECTED) {
                recordOutcome("quality_rejected", started);
                return new UrlReadResult(null, new BrowserReadFailure(requestedUrl,
                        "browser content rejected as " + quality.name().toLowerCase()));
            }
            String sourceUrl = normalizeResponseUrl(rendered.url(), requestedUrl);
            String renderedContent = rendered.content().length() > maxContentCharacters
                    ? rendered.content().substring(0, maxContentCharacters) : rendered.content();
            boolean truncated = rendered.truncated() || rendered.content().length() > maxContentCharacters;
            String content = "Source URL: " + sourceUrl + "\n"
                    + "Content type: " + rendered.contentType() + "\n"
                    + "Truncated: " + truncated + "\nRendered page content:\n" + renderedContent;
            recordOutcome("success", started);
            LOGGER.info("Browser render succeeded url_index={} truncated={}", index, rendered.truncated());
            return new UrlReadResult(new KnowledgeCandidate(
                    "browser-" + index, KnowledgeSource.BROWSER, content, index, sourceUrl), null);
        } catch (BrowserContentException exception) {
            recordOutcome("failure", started);
            return new UrlReadResult(null, new BrowserReadFailure(requestedUrl, exception.getMessage()));
        } catch (RuntimeException exception) {
            recordOutcome("failure", started);
            return new UrlReadResult(null, new BrowserReadFailure(requestedUrl, "browser worker is unavailable"));
        }
    }

    private record UrlReadResult(KnowledgeCandidate candidate, BrowserReadFailure failure) { }

    public List<String> urlsIn(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        Set<String> urls = new LinkedHashSet<>();
        Matcher matcher = URL_PATTERN.matcher(message);
        while (matcher.find()) {
            String candidate = trimTrailingPunctuation(matcher.group());
            try {
                URI uri = new URI(candidate).normalize();
                if (uri.getScheme() == null || uri.getHost() == null
                        || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
                    throw new BrowserContentException("invalid URL: " + candidate);
                }
                urls.add(uri.toString());
            } catch (URISyntaxException exception) {
                throw new BrowserContentException("invalid URL: " + candidate, exception);
            }
        }
        return List.copyOf(urls);
    }

    private String normalizeResponseUrl(String responseUrl, String requestedUrl) {
        return responseUrl == null || responseUrl.isBlank() ? requestedUrl : responseUrl;
    }

    private String trimTrailingPunctuation(String value) {
        return value.replaceFirst("[.,;:!?]+$", "");
    }

    private void recordOutcome(String outcome, long started) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("minikun.browser.render", "outcome", outcome).increment();
        meterRegistry.timer("minikun.browser.render.duration", "outcome", outcome)
                .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
}
