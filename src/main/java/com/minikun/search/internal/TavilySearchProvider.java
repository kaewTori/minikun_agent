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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Tavily API adapter. The API key is supplied through the RestClient default header. */
public final class TavilySearchProvider implements SearchProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(TavilySearchProvider.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String apiKey;
    private final boolean enabled;
    private final String searchDepth;

    public TavilySearchProvider(
            RestClient restClient,
            ObjectMapper objectMapper,
            Clock clock,
            String apiKey,
            boolean enabled,
            String searchDepth) {
        this.restClient = Objects.requireNonNull(restClient, "rest client must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.apiKey = Objects.requireNonNullElse(apiKey, "").trim();
        this.enabled = enabled;
        this.searchDepth = normalizeDepth(searchDepth);
    }

    /** Allows configuration to avoid routing every request through a knowingly unavailable provider. */
    public boolean configured() {
        return enabled && !apiKey.isBlank();
    }

    @Override
    public SearchProviderResponse search(SearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Instant started = clock.instant();
        try {
            if (!configured()) {
                throw new SearchProviderUnavailableException("Tavily is not configured");
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("query", request.query());
            payload.put("search_depth", searchDepth);
            payload.put("max_results", request.resultLimit());
            payload.put("include_answer", false);
            payload.put("include_raw_content", false);
            payload.put("include_images", false);
            if ("news".equalsIgnoreCase(request.options().category())) {
                payload.put("topic", "news");
            }
            String timeRange = normalizeTimeRange(request.options().timeRange());
            if (!timeRange.isBlank()) {
                payload.put("time_range", timeRange);
            }

            LOGGER.info("process=tavily event=request request_id={} query={} result_limit={}",
                    request.requestId(), request.query(), request.resultLimit());
            String body = restClient.post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            SearchProviderResponse response = new SearchProviderResponse(parseResults(body));
            LOGGER.info("process=tavily event=completed request_id={} duration_ms={} result_count={}",
                    request.requestId(), Duration.between(started, clock.instant()).toMillis(),
                    response.results().size());
            return response;
        } catch (ResourceAccessException exception) {
            logFailure(request, started, exception);
            throw new SearchProviderUnavailableException("Tavily is unavailable", exception);
        } catch (RestClientResponseException exception) {
            logFailure(request, started, exception);
            int status = exception.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new SearchProviderUnavailableException("Tavily authentication failed", exception);
            }
            if (status == 429 || status >= 500) {
                throw new SearchProviderUnavailableException(
                        "Tavily returned retryable HTTP status " + status, exception);
            }
            throw new SearchExecutionException("Tavily returned HTTP status " + status, exception);
        } catch (SearchProviderUnavailableException | SearchExecutionException exception) {
            logFailure(request, started, exception);
            throw exception;
        } catch (RuntimeException exception) {
            logFailure(request, started, exception);
            throw new SearchExecutionException("Tavily response could not be processed", exception);
        }
    }

    private List<SearchResult> parseResults(String body) {
        if (body == null || body.isBlank()) {
            throw new SearchExecutionException("Tavily returned an empty response");
        }
        try {
            JsonNode results = objectMapper.readTree(body).path("results");
            if (!results.isArray()) {
                throw new SearchExecutionException("Tavily response has no results array");
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
                double score = number(result, "score");
                mapped.add(new SearchResult(title, url, content,
                        new SearchSource("tavily", url, clock.instant()), position++, score,
                        SearchPublicationTime.parse(result, "published_date", "publishedAt", "date")));
            }
            return List.copyOf(mapped);
        } catch (SearchExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SearchExecutionException("Tavily response is not valid JSON", exception);
        }
    }

    private void logFailure(SearchRequest request, Instant started, RuntimeException exception) {
        LOGGER.warn("process=tavily event=failed request_id={} duration_ms={} failure_type={} message={}",
                request.requestId(), Duration.between(started, clock.instant()).toMillis(),
                exception.getClass().getSimpleName(), exception.getMessage());
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    private double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber()
                ? Math.max(0.0, Math.min(1.0, value.asDouble())) : 0.0;
    }

    private String normalizeDepth(String value) {
        return "advanced".equalsIgnoreCase(value) ? "advanced" : "basic";
    }

    private String normalizeTimeRange(String value) {
        if (value == null) {
            return "";
        }
        return switch (value.trim().toLowerCase()) {
            case "day", "week", "month", "year" -> value.trim().toLowerCase();
            default -> "";
        };
    }
}
