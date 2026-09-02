package com.minikun.visual;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Calls TinyGrad's local {@code /health} endpoint with a short, UI-friendly timeout. */
public final class TinyGradRuntimeStatusClient implements TinyGradRuntimeStatusReader {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final URI endpoint;
    private final Duration timeout;
    private final TinyGradRuntimeAccessCoordinator runtimeAccess;
    private final AtomicReference<RuntimeStatus> latest = new AtomicReference<>();

    public TinyGradRuntimeStatusClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock,
            String baseUrl,
            Duration timeout) {
        this(httpClient, objectMapper, clock, baseUrl, timeout, new TinyGradRuntimeAccessCoordinator());
    }

    public TinyGradRuntimeStatusClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock,
            String baseUrl,
            Duration timeout,
            TinyGradRuntimeAccessCoordinator runtimeAccess) {
        this.httpClient = Objects.requireNonNull(httpClient, "HTTP client must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("TinyGrad health timeout must be positive");
        }
        this.endpoint = URI.create(normalize(baseUrl) + "/health");
        this.timeout = timeout;
        this.runtimeAccess = Objects.requireNonNull(runtimeAccess, "runtime access must not be null");
    }

    @Override
    public RuntimeStatus read() {
        Instant checkedAt = clock.instant();
        long started = System.nanoTime();
        TinyGradRuntimeAccessCoordinator.HealthLease lease = runtimeAccess.tryAcquireHealth();
        if (lease == null) {
            return duringGeneration(checkedAt);
        }
        try (lease) {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = elapsedMillis(started);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return offline(checkedAt, latency);
            }
            JsonNode root = objectMapper.readTree(response.body());
            String serviceStatus = root.path("status").asText("");
            if (!"ok".equalsIgnoreCase(serviceStatus) && !"up".equalsIgnoreCase(serviceStatus)) {
                return offline(checkedAt, latency);
            }
            JsonNode memory = root.path("memory");
            long activeBytes = nonNegative(memory.path("active_bytes").asLong(-1));
            long cachedBytes = nonNegative(memory.path("cached_bytes").asLong(-1));
            long trackedBytes = nonNegative(memory.path("tracked_bytes").asLong(-1));
            long budgetBytes = nonNegative(root.path("vram_budget_bytes").asLong(-1));
            double usedPercent = activeBytes >= 0 && budgetBytes > 0
                    ? Math.round(activeBytes * 1_000.0 / budgetBytes) / 10.0 : -1.0;
            RuntimeStatus status = new RuntimeStatus(
                    true,
                    "ONLINE",
                    endpoint.toString(),
                    latency,
                    new RuntimeMemory(activeBytes, cachedBytes, trackedBytes),
                    budgetBytes,
                    usedPercent,
                    nonNegative(root.path("request_count").asLong(-1)),
                    nonNegative(root.path("recycle_requests").asLong(-1)),
                    root.path("recycle_requested").asBoolean(false),
                    nullableText(root.path("recycle_reason")),
                    nullableText(root.path("last_resolution")),
                    nullableInteger(root.path("last_prompt_chunks")),
                    Math.max(0, root.path("compiler_processes").asInt(0)),
                    Math.max(0, root.path("compile_workers").asInt(0)),
                    checkedAt);
            latest.set(status);
            return status;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return offline(checkedAt, elapsedMillis(started));
        } catch (IOException | RuntimeException exception) {
            return offline(checkedAt, elapsedMillis(started));
        }
    }

    private RuntimeStatus duringGeneration(Instant checkedAt) {
        RuntimeStatus previous = latest.get();
        if (previous == null) {
            return new RuntimeStatus(
                    true, "GENERATING", endpoint.toString(), 0,
                    new RuntimeMemory(-1, -1, -1), -1, -1,
                    -1, -1, false, null, null, null, 0, 0, checkedAt);
        }
        return new RuntimeStatus(
                true, "GENERATING", previous.endpoint(), 0, previous.memory(),
                previous.vram_budget_bytes(), previous.vram_used_percent(), previous.request_count(),
                previous.recycle_requests(), previous.recycle_requested(), previous.recycle_reason(),
                previous.last_resolution(), previous.last_prompt_chunks(), previous.compiler_processes(),
                previous.compile_workers(), checkedAt);
    }

    private RuntimeStatus offline(Instant checkedAt, long latency) {
        return new RuntimeStatus(
                false, "OFFLINE", endpoint.toString(), latency,
                new RuntimeMemory(-1, -1, -1), -1, -1,
                -1, -1, false, null, null, null, 0, 0, checkedAt);
    }

    private static long nonNegative(long value) {
        return value < 0 ? -1 : value;
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000L);
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()
                ? null : node.asText();
    }

    private static Integer nullableInteger(JsonNode node) {
        return node != null && node.isIntegralNumber() ? node.asInt() : null;
    }

    private static String normalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("TinyGrad base URL must not be blank");
        }
        return baseUrl.strip().replaceAll("/+$", "");
    }
}
