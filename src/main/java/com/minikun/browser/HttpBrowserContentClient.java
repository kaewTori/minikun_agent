package com.minikun.browser;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HttpBrowserContentClient implements BrowserContentClient {
    private static final int MAX_ATTEMPTS = 2;
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpBrowserContentClient.class);
    private final RestClient restClient;
    private final String token;

    public HttpBrowserContentClient(RestClient restClient, String token) {
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
                    throw new BrowserContentException("browser worker timed out or is unavailable", exception);
                }
                logRetry(url, attempt, exception.getClass().getSimpleName());
            } catch (RestClientResponseException exception) {
                if (exception.getStatusCode().is4xxClientError() || attempt == MAX_ATTEMPTS) {
                    throw new BrowserContentException(
                            "browser worker returned HTTP " + exception.getStatusCode().value(), exception);
                }
                logRetry(url, attempt, "HTTP " + exception.getStatusCode().value());
            } catch (BrowserContentException exception) {
                if (attempt == MAX_ATTEMPTS || !exception.getMessage().contains("invalid response")) {
                    throw exception;
                }
                logRetry(url, attempt, exception.getMessage());
            }
        }
        throw new IllegalStateException("browser render attempts exhausted");
    }

    private BrowserContent renderOnce(String url) {
        RestClient.RequestBodySpec request = restClient.post().uri("/render")
                .contentType(MediaType.APPLICATION_JSON);
        if (!token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }
        Response response = request.body(new Request(url)).retrieve().body(Response.class);
        if (response == null || response.content() == null) {
            throw new BrowserContentException("browser worker returned an invalid response");
        }
        return new BrowserContent(response.url(), response.content(), response.contentType(), response.truncated());
    }

    private void logRetry(String url, int attempt, String reason) {
        LOGGER.warn("Retrying browser render url={} attempt={}/{} reason={}",
                url, attempt + 1, MAX_ATTEMPTS, reason);
    }

    private record Request(String url) { }

    private record Response(
            String url,
            String content,
            @JsonProperty("content_type") String contentType,
            boolean truncated) { }
}
