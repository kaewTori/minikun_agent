package com.minikun.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Fetches LLM-ready Fit Markdown from the Crawl4AI Docker API. */
public final class Crawl4AiBrowserContentClient implements BrowserContentClient {
    private static final int MAX_ATTEMPTS = 2;
    private static final Logger LOGGER = LoggerFactory.getLogger(Crawl4AiBrowserContentClient.class);
    private final RestClient restClient;
    private final String token;

    public Crawl4AiBrowserContentClient(RestClient restClient, String token) {
        this.restClient = restClient;
        this.token = token == null ? "" : token;
    }

    @Override
    public BrowserContent render(String url) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return renderOnce(url);
            } catch (ResourceAccessException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new BrowserContentException("Crawl4AI timed out or is unavailable", exception);
                }
                logRetry(url, attempt, exception.getClass().getSimpleName());
            } catch (RestClientResponseException exception) {
                if (exception.getStatusCode().is4xxClientError() || attempt == MAX_ATTEMPTS) {
                    throw new BrowserContentException(
                            "Crawl4AI returned HTTP " + exception.getStatusCode().value(), exception);
                }
                logRetry(url, attempt, "HTTP " + exception.getStatusCode().value());
            } catch (BrowserContentException exception) {
                if (attempt == MAX_ATTEMPTS || !exception.getMessage().contains("invalid response")) {
                    throw exception;
                }
                logRetry(url, attempt, exception.getMessage());
            }
        }
        throw new IllegalStateException("Crawl4AI render attempts exhausted");
    }

    private BrowserContent renderOnce(String url) {
        RestClient.RequestBodySpec request = restClient.post().uri("/md")
                .contentType(MediaType.APPLICATION_JSON);
        if (!token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }
        Response response = request.body(new Request(url, "fit", "0"))
                .retrieve().body(Response.class);
        if (response == null || !response.success()
                || response.markdown() == null || response.markdown().isBlank()) {
            throw new BrowserContentException("Crawl4AI returned an invalid response");
        }
        String responseUrl = response.url() == null || response.url().isBlank() ? url : response.url();
        return new BrowserContent(responseUrl, response.markdown(), "text/markdown", false);
    }

    private void logRetry(String url, int attempt, String reason) {
        LOGGER.warn("Retrying Crawl4AI render url={} attempt={}/{} reason={}",
                url, attempt + 1, MAX_ATTEMPTS, reason);
    }

    private record Request(String url, String f, String c) { }

    private record Response(String url, String markdown, boolean success) { }
}
