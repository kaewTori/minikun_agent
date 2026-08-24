package com.minikun.systemhealth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Reads the canonical NV device counters from TinyGrad's semantic health endpoint. */
final class HttpNvAllocatorMemoryProbe implements NvAllocatorMemoryProbe {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final Duration timeout;

    HttpNvAllocatorMemoryProbe(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String baseUrl,
            Duration timeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "HTTP client must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("NV allocator health timeout must be positive");
        }
        this.endpoint = healthEndpoint(baseUrl);
        this.timeout = timeout;
    }

    @Override
    public Map<String, Object> read() {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return unavailable();
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode nv = root.path("allocator_memory").path("devices").path("NV");
            if (!"up".equalsIgnoreCase(root.path("status").asText())
                    || !nv.path("current_bytes").canConvertToLong()
                    || !nv.path("peak_bytes").canConvertToLong()) {
                return unavailable();
            }
            long currentBytes = nv.path("current_bytes").asLong(-1);
            long peakBytes = nv.path("peak_bytes").asLong(-1);
            if (currentBytes < 0 || peakBytes < currentBytes) {
                return unavailable();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "UP");
            result.put("device", "NV");
            result.put("current_bytes", currentBytes);
            result.put("peak_bytes", peakBytes);
            return Map.copyOf(result);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unavailable();
        } catch (IOException | RuntimeException exception) {
            return unavailable();
        }
    }

    private Map<String, Object> unavailable() {
        return Map.of("status", "UNKNOWN", "device", "NV");
    }

    private static URI healthEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("TinyGrad base URL must not be blank");
        }
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        return URI.create(normalized.endsWith("/v1") ? normalized + "/health" : normalized + "/v1/health");
    }
}
