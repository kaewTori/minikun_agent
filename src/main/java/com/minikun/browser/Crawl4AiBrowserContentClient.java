package com.minikun.browser;

import tools.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** A single render returns both filtered and full Markdown, with bounded dynamic-page waits. */
public final class Crawl4AiBrowserContentClient implements BrowserContentClient {
    private final RestClient restClient;
    private final String token;
    private final Duration pageTimeout;
    private final Duration settleDelay;
    private final Duration retryDelay;
    private final BrowserContentQualityClassifier quality = new BrowserContentQualityClassifier();

    public Crawl4AiBrowserContentClient(RestClient restClient, String token) {
        this(restClient, token, Duration.ofSeconds(15), Duration.ofMillis(1500), Duration.ofSeconds(1));
    }

    public Crawl4AiBrowserContentClient(RestClient restClient, String token,
            Duration pageTimeout, Duration settleDelay, Duration retryDelay) {
        this.restClient = restClient;
        this.token = token == null ? "" : token;
        if (pageTimeout.isNegative() || pageTimeout.isZero() || settleDelay.isNegative() || retryDelay.isNegative()
                || pageTimeout.compareTo(Duration.ofSeconds(60)) > 0
                || settleDelay.compareTo(Duration.ofSeconds(5)) > 0 || retryDelay.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("browser wait durations are outside supported bounds");
        }
        this.pageTimeout = pageTimeout;
        this.settleDelay = settleDelay;
        this.retryDelay = retryDelay;
    }

    @Override
    public BrowserContent render(String url) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return renderOnce(url);
            } catch (ResourceAccessException exception) {
                if (attempt == 1) throw new BrowserContentException("Crawl4AI timed out or is unavailable", exception);
                pause(retryDelay);
            } catch (RestClientResponseException exception) {
                int status = exception.getStatusCode().value();
                if (attempt == 1 || status != 429 && !exception.getStatusCode().is5xxServerError()) {
                    throw new BrowserContentException("Crawl4AI returned HTTP " + status, exception);
                }
                pause(retryAfter(exception));
            } catch (RetryablePageFailure exception) {
                if (attempt == 1) throw new BrowserContentException(exception.getMessage());
                pause(retryDelay);
            }
        }
        throw new IllegalStateException("Crawl4AI render attempts exhausted");
    }

    private BrowserContent renderOnce(String url) {
        RestClient.RequestBodySpec request = restClient.post().uri("/crawl").contentType(MediaType.APPLICATION_JSON);
        if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
        Map<String, Object> parameters = Map.of(
                "wait_until", "domcontentloaded", "wait_for", "css:body", "wait_for_timeout", 3_000,
                "page_timeout", pageTimeout.toMillis(), "delay_before_return_html", settleDelay.toMillis() / 1000.0,
                "scan_full_page", true, "max_scroll_steps", 8, "scroll_delay", 0.2,
                "cache_mode", Map.of("type", "CacheMode", "params", "bypass"),
                "markdown_generator", Map.of("type", "DefaultMarkdownGenerator", "params", Map.of(
                        "content_filter", Map.of("type", "PruningContentFilter", "params", Map.of()))));
        JsonNode response = request.body(Map.of("urls", List.of(url),
                "browser_config", Map.of("type", "BrowserConfig", "params", Map.of("headless", true)),
                "crawler_config", Map.of("type", "CrawlerRunConfig", "params", parameters)))
                .retrieve().body(JsonNode.class);
        if (response == null || !response.path("success").asBoolean() || !response.path("results").isArray()
                || response.path("results").isEmpty()) {
            throw new RetryablePageFailure("Crawl4AI returned an invalid response");
        }
        JsonNode result = response.path("results").get(0);
        JsonNode markdown = result.path("markdown");
        String raw = markdown.isTextual() ? markdown.asText() : markdown.path("raw_markdown").asText("");
        String fit = markdown.path("fit_markdown").asText("");
        String content = fit.strip().length() >= 200 ? fit : raw.isBlank() ? fit : raw;
        if (quality.isChallenge(result.path("html").asText("")) || quality.isChallenge(raw)
                || quality.isChallenge(fit)) {
            throw new BrowserContentException("Cloudflare/CAPTCHA verification required; open the page in Minikun's browser session and verify manually");
        }
        int status = result.path("status_code").asInt(200);
        if (status == 429 || status >= 500) throw new RetryablePageFailure("website returned HTTP " + status);
        if (status >= 400) throw new BrowserContentException("website returned HTTP " + status);
        if (!result.path("success").asBoolean() || content.isBlank()) {
            throw new RetryablePageFailure("Crawl4AI could not read page content");
        }
        String resolved = result.path("redirected_url").asText("");
        if (resolved.isBlank()) resolved = result.path("url").asText(url);
        return new BrowserContent(resolved, content, "text/markdown", false, raw);
    }

    private Duration retryAfter(RestClientResponseException exception) {
        String value = exception.getResponseHeaders() == null ? null : exception.getResponseHeaders().getFirst("Retry-After");
        try {
            long millis;
            try {
                millis = Math.multiplyExact(Long.parseLong(value), 1000);
            } catch (NumberFormatException ignored) {
                millis = Duration.between(java.time.Instant.now(), java.time.ZonedDateTime.parse(value,
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();
            }
            return Duration.ofMillis(Math.max(retryDelay.toMillis(), Math.min(10_000, millis)));
        } catch (RuntimeException ignored) {
            return retryDelay;
        }
    }

    static void pause(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BrowserContentException("browser read interrupted", exception);
        }
    }

    private static final class RetryablePageFailure extends RuntimeException {
        RetryablePageFailure(String message) { super(message); }
    }
}
