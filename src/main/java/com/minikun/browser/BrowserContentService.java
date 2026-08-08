package com.minikun.browser;

import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    public BrowserContentService(BrowserContentClient client, boolean enabled, int maxUrls) {
        this(client, enabled, maxUrls, null);
    }

    public BrowserContentService(
            BrowserContentClient client, boolean enabled, int maxUrls, MeterRegistry meterRegistry) {
        this.client = client;
        this.enabled = enabled;
        this.maxUrls = maxUrls;
        this.meterRegistry = meterRegistry;
    }

    public List<KnowledgeCandidate> read(String message) {
        List<String> urls = urlsIn(message);
        if (urls.isEmpty() || !enabled) {
            return List.of();
        }
        if (urls.size() > maxUrls) {
            throw new BrowserContentException("too many links (maximum " + maxUrls + ")");
        }
        List<KnowledgeCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < urls.size(); index++) {
            String requestedUrl = urls.get(index);
            BrowserContent rendered;
            long started = System.nanoTime();
            try {
                rendered = client.render(requestedUrl);
            } catch (BrowserContentException exception) {
                recordOutcome("failure", started);
                throw exception;
            } catch (RuntimeException exception) {
                recordOutcome("failure", started);
                throw new BrowserContentException("browser worker is unavailable", exception);
            }
            if (rendered == null || rendered.content().isBlank()) {
                recordOutcome("failure", started);
                throw new BrowserContentException("browser worker returned empty content");
            }
            String sourceUrl = normalizeResponseUrl(rendered.url(), requestedUrl);
            String content = "Source URL: " + sourceUrl + "\n"
                    + "Content type: " + rendered.contentType() + "\n"
                    + "Truncated: " + rendered.truncated() + "\n"
                    + "Rendered page content:\n" + rendered.content();
            candidates.add(new KnowledgeCandidate(
                    "browser-" + index, KnowledgeSource.BROWSER, content, index));
            recordOutcome("success", started);
            LOGGER.info("Browser render succeeded url_index={} truncated={}", index, rendered.truncated());
        }
        return List.copyOf(candidates);
    }

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
