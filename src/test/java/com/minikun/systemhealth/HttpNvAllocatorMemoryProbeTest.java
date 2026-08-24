package com.minikun.systemhealth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class HttpNvAllocatorMemoryProbeTest {
    @Test
    void readsNvDeviceCountersInsteadOfAggregateAllocatorCounters() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"status":"up","allocator_memory":{"current_bytes":999,"peak_bytes":1000,
                 "devices":{"NV":{"current_bytes":123,"peak_bytes":456},
                 "PYTHON":{"current_bytes":876,"peak_bytes":877}}}}
                """);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        Map<String, Object> result = probe(client).read();

        assertEquals("UP", result.get("status"));
        assertEquals("NV", result.get("device"));
        assertEquals(123L, result.get("current_bytes"));
        assertEquals(456L, result.get("peak_bytes"));
    }

    @Test
    void returnsUnknownWithoutLeakingProbeFailure() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("offline"));

        assertEquals(Map.of("status", "UNKNOWN", "device", "NV"), probe(client).read());
    }

    private HttpNvAllocatorMemoryProbe probe(HttpClient client) {
        return new HttpNvAllocatorMemoryProbe(
                client, new ObjectMapper(), "http://127.0.0.1:8001/v1", Duration.ofSeconds(1));
    }
}
