package com.minikun.model.tinygrad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import com.fasterxml.jackson.databind.ObjectMapper;

class TinyGradHealthIndicatorTest {
    @Test
    void reportsSemanticRuntimeDetailsWhenReady() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"status":"up","model":"Gemma 4 E4B","scheduler":{"queued":2,"active":true,"completed":7},
                 "contexts":{"size":1,"in_use":1,"waiting":0},
                 "batcher":{"enabled":true,"batch_size":4,"queued":1,"failures":0},
                 "allocator_memory":{"current_bytes":123456,"peak_bytes":234567,"devices":{"NV":{"current_bytes":123456,"peak_bytes":234567}}}}
                """);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        var health = indicator(client, true).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("READY", health.getDetails().get("runtimeStatus"));
        assertEquals("Gemma 4 E4B", health.getDetails().get("model"));
        assertEquals(4, health.getDetails().get("batchSize"));
        assertEquals(2, health.getDetails().get("schedulerQueued"));
        assertEquals(123456L, health.getDetails().get("allocatorCurrentBytes"));
        assertEquals(234567L, health.getDetails().get("allocatorPeakBytes"));
    }

    @Test
    void reportsDownWhenRequiredRuntimeIsUnavailable() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("offline"));

        var health = indicator(client, true).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("UNAVAILABLE", health.getDetails().get("runtimeStatus"));
        assertEquals(true, health.getDetails().get("required"));
    }

    @Test
    void keepsApplicationUpWhenUnusedStandbyIsUnavailable() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        var health = indicator(client, false).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("STANDBY_UNAVAILABLE", health.getDetails().get("runtimeStatus"));
        assertEquals(false, health.getDetails().get("required"));
    }

    @Test
    void rejectsMalformedRuntimeStatusWhenRequired() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"status\":\"starting\"}");
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        var health = indicator(client, true).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("INVALID_RUNTIME_STATUS", health.getDetails().get("reason"));
    }

    private TinyGradHealthIndicator indicator(HttpClient client, boolean required) {
        return new TinyGradHealthIndicator(
                client, new ObjectMapper(), "http://127.0.0.1:8001/v1", Duration.ofSeconds(1), required);
    }
}
