package com.minikun.search.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.search.SearchExecutionException;
import com.minikun.search.SearchProvider;
import com.minikun.search.SearchProviderUnavailableException;
import com.minikun.search.model.SearchProviderResponse;
import com.minikun.search.model.SearchRequest;
import com.minikun.search.model.SearchResult;
import com.minikun.search.model.SearchSource;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

public final class SearXNGProvider implements SearchProvider {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SearXNGProvider(RestClient restClient, ObjectMapper objectMapper, Clock clock) {
        this.restClient = Objects.requireNonNull(restClient, "rest client must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public SearchProviderResponse search(SearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            String body = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search")
                            .queryParam("q", request.query())
                            .queryParam("format", "json")
                            .queryParam("number_of_results", request.resultLimit())
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            return new SearchProviderResponse(parseResults(body));
        } catch (ResourceAccessException exception) {
            throw new SearchProviderUnavailableException("SearXNG is unavailable", exception);
        } catch (RestClientResponseException exception) {
            throw new SearchProviderUnavailableException(
                    "SearXNG returned HTTP status " + exception.getStatusCode().value(), exception);
        } catch (SearchExecutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SearchExecutionException("SearXNG response could not be processed", exception);
        }
    }

    private List<SearchResult> parseResults(String body) {
        if (body == null || body.isBlank()) {
            throw new SearchExecutionException("SearXNG returned an empty response");
        }
        try {
            JsonNode results = objectMapper.readTree(body).path("results");
            if (!results.isArray()) {
                throw new SearchExecutionException("SearXNG response has no results array");
            }

            List<SearchResult> mapped = new ArrayList<>();
            int position = 1;
            for (JsonNode result : results) {
                String title = text(result, "title");
                String url = text(result, "url");
                String content = text(result, "content");
                if (title.isBlank() || url.isBlank() || content.isBlank()) {
                    continue;
                }
                SearchSource source = new SearchSource("searxng", url, clock.instant());
                mapped.add(new SearchResult(title, url, content, source, position));
                position++;
            }
            return List.copyOf(mapped);
        } catch (SearchExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SearchExecutionException("SearXNG response is not valid JSON", exception);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }
}