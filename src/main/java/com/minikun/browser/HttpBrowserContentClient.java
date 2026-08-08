package com.minikun.browser;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

public final class HttpBrowserContentClient implements BrowserContentClient {
    private final RestClient restClient;
    private final String token;

    public HttpBrowserContentClient(RestClient restClient, String token) {
        this.restClient = restClient;
        this.token = token == null ? "" : token;
    }

    @Override
    public BrowserContent render(String url) {
        try {
            RestClient.RequestBodySpec request = restClient.post().uri("/render")
                    .contentType(MediaType.APPLICATION_JSON);
            if (!token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
            Response response = request.body(new Request(url)).retrieve().body(Response.class);
            if (response == null || response.content == null) {
                throw new BrowserContentException("browser worker returned an invalid response");
            }
            return new BrowserContent(response.url, response.content, response.contentType, response.truncated);
        } catch (ResourceAccessException exception) {
            throw new BrowserContentException("browser worker timed out or is unavailable", exception);
        } catch (RestClientResponseException exception) {
            throw new BrowserContentException("browser worker returned HTTP " + exception.getStatusCode().value(), exception);
        }
    }

    private record Request(String url) { }

    private static final class Response {
        String url;
        String content;
        @JsonProperty("content_type") String contentType;
        boolean truncated;
    }
}
