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
import com.minikun.search.model.ImageSearchResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SearXNGProvider implements SearchProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearXNGProvider.class);
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
        Instant started = clock.instant();
        SearchProviderResponse response = null;
        RuntimeException failure = null;
        try {
            var uri = requestUri(request, true);
            LOGGER.info("process=searxng event=request request_id={} query={} result_limit={}",
                    request.requestId(), request.query(), request.resultLimit());
            String body;
            try {
                body = execute(uri);
            } catch (RestClientResponseException exception) {
                if (exception.getStatusCode().is5xxServerError() && hasOptionalFilters(request)) {
                    LOGGER.warn("process=searxng event=retry_without_filters request_id={} status={}",
                            request.requestId(), exception.getStatusCode().value());
                    body = execute(requestUri(request, false));
                } else {
                    throw exception;
                }
            }
            response = request.options().category().equalsIgnoreCase(
                    com.minikun.search.model.SearchOptions.IMAGE_CATEGORY)
                    ? new SearchProviderResponse(List.of(), parseImageResults(body))
                    : new SearchProviderResponse(parseResults(body));
            return response;
        } catch (ResourceAccessException exception) {
            failure = exception;
            throw new SearchProviderUnavailableException("SearXNG is unavailable", exception);
        } catch (RestClientResponseException exception) {
            failure = exception;
            throw new SearchProviderUnavailableException(
                    "SearXNG returned HTTP status " + exception.getStatusCode().value(), exception);
        } catch (SearchExecutionException exception) {
            failure = exception;
            throw exception;
        } catch (RuntimeException exception) {
            failure = exception;
            throw new SearchExecutionException("SearXNG response could not be processed", exception);
        } finally {
            logProviderOutcome(request, response, failure, Duration.between(started, clock.instant()));
        }
    }

    private java.net.URI requestUri(SearchRequest request, boolean includeOptionalFilters) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/search")
                    .queryParam("q", request.query())
                    .queryParam("format", "json")
                    .queryParam("number_of_results", request.resultLimit());
        if (includeOptionalFilters) {
            builder
                    .queryParamIfPresent("language", optional(request.options().language()))
                    .queryParamIfPresent("categories", optional(request.options().category()))
                    .queryParamIfPresent("time_range", optional(normalizeTimeRange(request.options().timeRange())))
                    .queryParamIfPresent("safesearch", request.options().safeSearch()
                            ? java.util.Optional.of("1") : java.util.Optional.empty());
        }
        return builder.build()
                .encode()
                .toUri();
    }

    private String execute(java.net.URI uri) {
        return restClient.get()
                .uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
    }

    private boolean hasOptionalFilters(SearchRequest request) {
        return !request.options().language().isBlank()
                || !request.options().category().isBlank()
                || !request.options().timeRange().isBlank()
                || request.options().safeSearch();
    }

    private java.util.Optional<String> optional(String value) {
        return value == null || value.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(value);
    }

    private String normalizeTimeRange(String value) {
        if (value == null) return "";
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "day", "month", "year" -> value.trim().toLowerCase(java.util.Locale.ROOT);
            // SearXNG has no week value. A month is the closest supported superset.
            case "week" -> "month";
            default -> "";
        };
    }

    private void logProviderOutcome(
            SearchRequest request,
            SearchProviderResponse response,
            RuntimeException failure,
            Duration duration) {
        try {
            if (failure == null) {
            LOGGER.info("process=searxng event=completed request_id={} duration_ms={} result_count={}",
                request.requestId(), duration.toMillis(), response.results().size() + response.images().size());
            } else {
            LOGGER.warn("process=searxng event=failed request_id={} duration_ms={} failure_type={} message={}",
                request.requestId(), duration.toMillis(), failure.getClass().getSimpleName(), failure.getMessage());
            }
        } catch (RuntimeException ignored) {
            // Logging must not affect provider behavior.
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
                mapped.add(new SearchResult(title, url, content, source, position++, 0.0,
                        SearchPublicationTime.parse(result, "publishedDate", "published_date", "published", "date")));
            }
            return List.copyOf(mapped);
        } catch (SearchExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SearchExecutionException("SearXNG response is not valid JSON", exception);
        }
    }

    private List<ImageSearchResult> parseImageResults(String body) {
        if (body == null || body.isBlank()) {
            throw new SearchExecutionException("SearXNG returned an empty response");
        }
        try {
            JsonNode results = objectMapper.readTree(body).path("results");
            if (!results.isArray()) {
                throw new SearchExecutionException("SearXNG response has no results array");
            }

            List<ImageSearchResult> mapped = new ArrayList<>();
            for (JsonNode result : results) {
                String imageUrl = text(result, "img_src");
                if (imageUrl.isBlank()) {
                    continue;
                }
                mapped.add(new ImageSearchResult(
                        imageUrl,
                        text(result, "title"),
                        text(result, "url"),
                        text(result, "content"),
                        text(result, "thumbnail_src"),
                        integer(result, "width"),
                        integer(result, "height"),
                        text(result, "engine"),
                        text(result, "license")));
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

    private Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToInt() && value.asInt() > 0 ? value.asInt() : null;
    }
}
