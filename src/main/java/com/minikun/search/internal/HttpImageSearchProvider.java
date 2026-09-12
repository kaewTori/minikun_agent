package com.minikun.search.internal;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.search.ImageSearchProvider;
import com.minikun.search.SearchException;
import com.minikun.search.SearchExecutionException;
import com.minikun.search.SearchProviderUnavailableException;
import com.minikun.search.model.ImageSearchRequest;
import com.minikun.search.model.ImageSearchResult;
import com.minikun.search.model.SearchProviderResponse;

/** Multipart adapter for a provider returning {"results":[...]} image metadata. */
public final class HttpImageSearchProvider implements ImageSearchProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpImageSearchProvider.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String endpoint;
    private final boolean enabled;

    public HttpImageSearchProvider(
            RestClient restClient,
            ObjectMapper objectMapper,
            Clock clock,
            String endpoint,
            boolean enabled) {
        this.restClient = Objects.requireNonNull(restClient, "rest client must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.endpoint = Objects.requireNonNullElse(endpoint, "").trim();
        this.enabled = enabled;
    }

    public boolean configured() {
        return enabled && !endpoint.isBlank();
    }

    @Override
    public SearchProviderResponse search(ImageSearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!configured()) {
            throw new SearchProviderUnavailableException("reverse image search is not configured");
        }
        Instant started = clock.instant();
        SearchProviderResponse response = null;
        RuntimeException failure = null;
        try {
            if (!clock.instant().isBefore(request.deadline())) {
                throw new SearchExecutionException("reverse image search deadline exceeded");
            }
            response = parse(execute(request));
            return response;
        } catch (ResourceAccessException exception) {
            failure = exception;
            throw new SearchProviderUnavailableException("reverse image search provider is unavailable", exception);
        } catch (RestClientResponseException exception) {
            failure = exception;
            throw new SearchProviderUnavailableException(
                    "reverse image search provider returned HTTP status " + exception.getStatusCode().value(),
                    exception);
        } catch (SearchException exception) {
            failure = exception;
            throw exception;
        } catch (RuntimeException exception) {
            failure = exception;
            throw new SearchExecutionException("reverse image search response could not be processed", exception);
        } finally {
            logOutcome(request, response, failure, Duration.between(started, clock.instant()));
        }
    }

    private String execute(ImageSearchRequest request) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        String filename = "reference" + extension(request.mimeType());
        ByteArrayResource image = new ByteArrayResource(request.bytes()) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        body.part("image", image)
                .contentType(MediaType.parseMediaType(request.mimeType()));
        body.part("limit", Integer.toString(request.resultLimit()));
        return restClient.post()
                .uri(URI.create(endpoint))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON)
                .body(body.build())
                .retrieve()
                .body(String.class);
    }

    private SearchProviderResponse parse(String body) {
        if (body == null || body.isBlank()) {
            throw new SearchExecutionException("reverse image search provider returned an empty response");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode results = root == null ? null : root.path("results");
            if (results == null || !results.isArray()) {
                results = root == null ? null : root.path("images");
            }
            if (results == null || !results.isArray()) {
                throw new SearchExecutionException(
                        "reverse image search response has no results array");
            }
            List<ImageSearchResult> images = new ArrayList<>();
            for (JsonNode result : results) {
                String imageUrl = text(result, "image_url", "imageUrl", "img_src", "image", "url");
                if (imageUrl.isBlank()) {
                    continue;
                }
                images.add(new ImageSearchResult(
                        imageUrl,
                        text(result, "title", "name"),
                        text(result, "source_url", "sourceUrl", "page_url", "pageUrl", "source"),
                        text(result, "description", "content", "snippet"),
                        text(result, "thumbnail_url", "thumbnailUrl", "thumbnail_src", "thumbnail"),
                        integer(result, "width"),
                        integer(result, "height"),
                        text(result, "provider", "engine"),
                        text(result, "license")));
            }
            return new SearchProviderResponse(List.of(), images);
        } catch (SearchExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SearchExecutionException(
                    "reverse image search response is not valid JSON", exception);
        }
    }

    private String text(JsonNode node, String... fields) {
        if (node == null) {
            return "";
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private Integer integer(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.canConvertToInt() && value.asInt() > 0 ? value.asInt() : null;
    }

    private String extension(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }

    private void logOutcome(
            ImageSearchRequest request,
            SearchProviderResponse response,
            RuntimeException failure,
            Duration duration) {
        try {
            if (failure == null) {
                LOGGER.info("process=reverse_image_search event=completed request_id={} duration_ms={} result_count={}",
                        request.requestId(), duration.toMillis(), response.images().size());
            } else {
                LOGGER.warn("process=reverse_image_search event=failed request_id={} duration_ms={} failure_type={}",
                        request.requestId(), duration.toMillis(), failure.getClass().getSimpleName());
            }
        } catch (RuntimeException ignored) {
            // Logging must not affect provider behavior.
        }
    }
}
