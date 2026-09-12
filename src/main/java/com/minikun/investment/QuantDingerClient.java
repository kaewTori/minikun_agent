package com.minikun.investment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/** Thin REST adapter for QuantDinger Agent Gateway R/B capabilities. */
public final class QuantDingerClient {
    private static final String API_PREFIX = "/api/agent/v1";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean enabled;
    private final String baseUrl;
    private final String agentToken;

    public QuantDingerClient(
            RestClient client, ObjectMapper objectMapper, Clock clock, boolean enabled, String baseUrl,
            String agentToken) {
        this.client = Objects.requireNonNull(client, "QuantDinger rest client must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "QuantDinger clock must not be null");
        this.enabled = enabled;
        this.baseUrl = Objects.requireNonNullElse(baseUrl, "").trim();
        this.agentToken = Objects.requireNonNullElse(agentToken, "").trim();
    }

    public boolean configured() {
        return enabled && !baseUrl.isBlank() && !agentToken.isBlank();
    }

    public Map<String, Object> health() {
        return get(API_PREFIX + "/health", Map.of(), false);
    }

    public Map<String, Object> runtimeOverview() {
        return get(API_PREFIX + "/runtime/overview", Map.of(), true);
    }

    public Map<String, Object> markets() {
        return get(API_PREFIX + "/markets", Map.of(), true);
    }

    public Map<String, Object> price(String market, String symbol) {
        return get(API_PREFIX + "/price", Map.of("market", required(market, "market"),
                "symbol", required(symbol, "symbol")), true);
    }

    public Map<String, Object> klines(String market, String symbol, String timeframe, int limit) {
        if (limit < 1 || limit > 2_000) throw new IllegalArgumentException("kline limit must be 1 to 2000");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("market", required(market, "market"));
        params.put("symbol", required(symbol, "symbol"));
        params.put("timeframe", required(timeframe, "timeframe"));
        params.put("limit", limit);
        return get(API_PREFIX + "/klines", params, true);
    }

    public Map<String, Object> compileStrategy(String code, Integer sourceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (code != null && !code.isBlank()) body.put("code", boundedCode(code));
        if (sourceId != null) body.put("source_id", sourceId);
        if (body.isEmpty()) throw new IllegalArgumentException("strategy code or source_id is required");
        return post(API_PREFIX + "/strategy-sources/compile", body, null, true);
    }

    public Map<String, Object> saveStrategySource(Map<String, Object> request, String idempotencyKey) {
        Objects.requireNonNull(request, "strategy source request must not be null");
        Map<String, Object> body = new LinkedHashMap<>(request);
        Object code = body.get("code");
        if (code != null) body.put("code", boundedCode(code.toString()));
        if (body.get("name") == null || body.get("name").toString().isBlank()) {
            throw new IllegalArgumentException("strategy source name is required");
        }
        return post(API_PREFIX + "/strategy-sources", body, required(idempotencyKey, "idempotency key"), true);
    }

    public Map<String, Object> strategyVersions(long sourceId) {
        if (sourceId < 1) throw new IllegalArgumentException("source_id must be positive");
        return get(API_PREFIX + "/strategy-sources/" + sourceId + "/versions", Map.of(), true);
    }

    public Map<String, Object> submitBacktest(Map<String, Object> request, String idempotencyKey) {
        Objects.requireNonNull(request, "backtest request must not be null");
        Map<String, Object> body = new LinkedHashMap<>(request);
        Object code = body.get("code");
        if (code == null || code.toString().isBlank()) throw new IllegalArgumentException("backtest code is required");
        body.put("code", boundedCode(code.toString()));
        return post(API_PREFIX + "/backtest/run", body, required(idempotencyKey, "idempotency key"), true);
    }

    public Map<String, Object> job(String jobId) {
        return get(API_PREFIX + "/jobs/" + pathSegment(jobId, "job_id"), Map.of(), true);
    }

    private Map<String, Object> get(String path, Map<String, Object> params, boolean authenticated) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
        params.forEach((key, value) -> builder.queryParam(key, value));
        URI uri = builder.build().toUri();
        try {
            return parse(client.get().uri(uri).headers(headers -> auth(headers, authenticated))
                    .retrieve().body(String.class));
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException("QuantDinger returned HTTP " + exception.getStatusCode().value(), exception);
        } catch (ResourceAccessException exception) {
            throw new IllegalStateException("QuantDinger is unavailable", exception);
        }
    }

    private Map<String, Object> post(String path, Map<String, Object> body, String idempotencyKey,
            boolean authenticated) {
        try {
            return parse(client.post().uri(path)
                    .headers(headers -> {
                        auth(headers, authenticated);
                        if (idempotencyKey != null) headers.set("Idempotency-Key", idempotencyKey);
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(String.class));
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException("QuantDinger returned HTTP " + exception.getStatusCode().value(), exception);
        } catch (ResourceAccessException exception) {
            throw new IllegalStateException("QuantDinger is unavailable", exception);
        }
    }

    private void auth(org.springframework.http.HttpHeaders headers, boolean authenticated) {
        if (authenticated && !agentToken.isBlank()) headers.setBearerAuth(agentToken);
    }

    private Map<String, Object> parse(String response) {
        if (response == null || response.isBlank()) throw new IllegalStateException("QuantDinger returned an empty response");
        try {
            JsonNode root = objectMapper.readTree(response);
            if (!root.isObject()) {
                Object data = root.isNull() ? Map.of() : objectMapper.convertValue(root, Object.class);
                return Map.of("data", data, "observed_at", clock.instant());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            if (root.has("code")) result.put("code", root.path("code").asInt());
            if (root.has("message")) result.put("message", root.path("message").asText(""));
            Object data = root.has("data")
                    ? objectMapper.convertValue(root.get("data"), Object.class)
                    : objectMapper.convertValue(root, MAP);
            result.put("data", data == null ? Map.of() : data);
            result.put("observed_at", clock.instant());
            return Map.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalStateException("QuantDinger response is invalid", exception);
        }
    }

    private String boundedCode(String value) {
        String normalized = Objects.requireNonNullElse(value, "");
        if (normalized.isBlank()) throw new IllegalArgumentException("strategy code must not be blank");
        if (normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512 * 1024) {
            throw new IllegalArgumentException("strategy code must be at most 512 KiB");
        }
        return normalized;
    }

    private String required(String value, String field) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private String pathSegment(String value, String field) {
        String normalized = required(value, field);
        if (!normalized.matches("[A-Za-z0-9._:-]{1,120}")) {
            throw new IllegalArgumentException(field + " contains invalid characters");
        }
        return normalized;
    }
}
