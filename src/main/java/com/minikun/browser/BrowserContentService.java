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
    private final BrowserUrlPolicy urlPolicy;
    private final int maxContentCharacters;
    private final BrowserContentQualityClassifier qualityClassifier;

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
        List<KnowledgeCandidate> candidates = new ArrayList<>();
        List<BrowserReadFailure> failures = new ArrayList<>();
        for (int index = 0; index < urls.size(); index++) {
            String requestedUrl = urls.get(index);
            try {
                urlPolicy.validate(new URI(requestedUrl));
            } catch (BrowserContentException exception) {
                failures.add(new BrowserReadFailure(requestedUrl, exception.getMessage()));
                recordOutcome("blocked", System.nanoTime());
                continue;
            } catch (URISyntaxException exception) {
                failures.add(new BrowserReadFailure(requestedUrl, "invalid URL"));
                continue;
            }
            BrowserContent rendered;
            long started = System.nanoTime();
            try {
                rendered = client.render(requestedUrl);
            } catch (BrowserContentException exception) {
                recordOutcome("failure", started);
                failures.add(new BrowserReadFailure(requestedUrl, exception.getMessage()));
                continue;
            } catch (RuntimeException exception) {
                recordOutcome("failure", started);
                failures.add(new BrowserReadFailure(requestedUrl, "browser worker is unavailable"));
                continue;
            }
            if (rendered == null || rendered.content().isBlank()) {
                recordOutcome("failure", started);
                failures.add(new BrowserReadFailure(requestedUrl, "browser worker returned empty content"));
                continue;
            }
            BrowserContentQuality quality = qualityClassifier.classify(rendered);
            if (quality == BrowserContentQuality.ERROR_PAGE
                    || quality == BrowserContentQuality.ACCESS_BLOCKED
                    || quality == BrowserContentQuality.PROMPT_INJECTION_SUSPECTED) {
                recordOutcome("quality_rejected", started);
                failures.add(new BrowserReadFailure(requestedUrl,
                        "browser content rejected as " + quality.name().toLowerCase()));
                continue;
            }
            String sourceUrl = normalizeResponseUrl(rendered.url(), requestedUrl);
            String renderedContent = rendered.content().length() > maxContentCharacters
                    ? rendered.content().substring(0, maxContentCharacters)
                    : rendered.content();
            boolean truncated = rendered.truncated() || rendered.content().length() > maxContentCharacters;
            String content = "Source URL: " + sourceUrl + "\n"
                    + "Content type: " + rendered.contentType() + "\n"
                    + "Truncated: " + truncated + "\n"
                    + "Rendered page content:\n" + renderedContent;
            candidates.add(new KnowledgeCandidate(
                    "browser-" + index, KnowledgeSource.BROWSER, content, index));
            recordOutcome("success", started);
            LOGGER.info("Browser render succeeded url_index={} truncated={}", index, rendered.truncated());
        }
        return new BrowserReadResult(candidates, failures);
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
