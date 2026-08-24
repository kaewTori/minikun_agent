package com.minikun.model.tinygrad;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Semantic readiness probe for the TinyGrad runtime, beyond a listening TCP socket. */
public final class TinyGradHealthIndicator implements HealthIndicator {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final Duration timeout;
    private final boolean required;

    TinyGradHealthIndicator(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String baseUrl,
            Duration timeout,
            boolean required) {
        this.httpClient = Objects.requireNonNull(httpClient, "HTTP client must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("TinyGrad health timeout must be positive");
        }
        this.endpoint = healthEndpoint(baseUrl);
        this.timeout = timeout;
        this.required = required;
    }

    @Override
    public Health health() {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return unavailable("HTTP_" + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!"up".equalsIgnoreCase(root.path("status").asText())) {
                return unavailable("INVALID_RUNTIME_STATUS");
            }
            JsonNode scheduler = root.path("scheduler");
            JsonNode contexts = root.path("contexts");
            JsonNode batcher = root.path("batcher");
            JsonNode allocatorMemory = root.path("allocator_memory");
            return Health.up()
                    .withDetail("required", required)
                    .withDetail("runtimeStatus", "READY")
                    .withDetail("model", root.path("model").asText("unknown"))
                    .withDetail("schedulerQueued", scheduler.path("queued").asInt(0))
                    .withDetail("schedulerActive", scheduler.path("active").asBoolean(false))
                    .withDetail("schedulerCompleted", scheduler.path("completed").asLong(0))
                    .withDetail("contexts", contexts.path("size").asInt(0))
                    .withDetail("contextsInUse", contexts.path("in_use").asInt(0))
                    .withDetail("contextsWaiting", contexts.path("waiting").asInt(0))
                    .withDetail("batchEnabled", batcher.path("enabled").asBoolean(false))
                    .withDetail("batchSize", batcher.path("batch_size").asInt(0))
                    .withDetail("batchQueued", batcher.path("queued").asInt(0))
                    .withDetail("batchFailures", batcher.path("failures").asLong(0))
                    .withDetail("allocatorCurrentBytes", allocatorMemory.path("current_bytes").asLong(0))
                    .withDetail("allocatorPeakBytes", allocatorMemory.path("peak_bytes").asLong(0))
                    .build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unavailable("INTERRUPTED");
        } catch (IOException | RuntimeException exception) {
            return unavailable(exception.getClass().getSimpleName());
        }
    }

    private Health unavailable(String reason) {
        Health.Builder builder = required ? Health.down() : Health.up();
        return builder
                .withDetail("required", required)
                .withDetail("runtimeStatus", required ? "UNAVAILABLE" : "STANDBY_UNAVAILABLE")
                .withDetail("reason", reason)
                .build();
    }

    private static URI healthEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("TinyGrad base URL must not be blank");
        }
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        return URI.create(normalized.endsWith("/v1") ? normalized + "/health" : normalized + "/v1/health");
    }
}
