package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TinyGradRuntimeStatusClientTest {
    private static final Instant NOW = Instant.parse("2026-09-02T03:04:05Z");

    @Test
    void readsLiveVramAndRuntimeCountersFromHealth() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"status":"ok","memory":{"active_bytes":11284860122,"cached_bytes":0,
                 "tracked_bytes":11284860122},"vram_budget_bytes":11811160064,
                 "request_count":7,"recycle_requests":50,"recycle_requested":false,
                 "last_resolution":"768x1280","last_prompt_chunks":2,
                 "compiler_processes":1,"compile_workers":4}
                """);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        TinyGradRuntimeStatusReader.RuntimeStatus status = client(client).read();

        assertTrue(status.online());
        assertEquals("ONLINE", status.status());
        assertEquals("http://127.0.0.1:8002/health", status.endpoint());
        assertEquals(11_284_860_122L, status.memory().active_bytes());
        assertEquals(11_811_160_064L, status.vram_budget_bytes());
        assertEquals(95.5, status.vram_used_percent());
        assertEquals(7L, status.request_count());
        assertEquals("768x1280", status.last_resolution());
        assertEquals(NOW, status.checked_at());
    }

    @Test
    void reportsOfflineWithoutLeakingConnectionFailure() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("secret failure"));

        TinyGradRuntimeStatusReader.RuntimeStatus status = client(client).read();

        assertFalse(status.online());
        assertEquals("OFFLINE", status.status());
        assertEquals(-1L, status.memory().active_bytes());
    }

    @Test
    void returnsGeneratingStatusWithoutProbingTinyGradDuringGeneration() throws Exception {
        HttpClient client = mock(HttpClient.class);
        TinyGradRuntimeAccessCoordinator runtimeAccess = new TinyGradRuntimeAccessCoordinator();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> generation = CompletableFuture.runAsync(() -> runtimeAccess.generate(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return null;
        }));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        TinyGradRuntimeStatusClient statusClient = new TinyGradRuntimeStatusClient(
                client, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                "http://127.0.0.1:8002", Duration.ofSeconds(1), runtimeAccess);

        try {
            TinyGradRuntimeStatusReader.RuntimeStatus status = statusClient.read();

            assertTrue(status.online());
            assertEquals("GENERATING", status.status());
            verifyNoInteractions(client);
        } finally {
            release.countDown();
            generation.join();
        }
    }

    private TinyGradRuntimeStatusClient client(HttpClient client) {
        return new TinyGradRuntimeStatusClient(
                client, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                "http://127.0.0.1:8002", Duration.ofSeconds(1));
    }
}
