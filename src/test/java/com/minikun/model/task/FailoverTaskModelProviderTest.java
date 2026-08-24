package com.minikun.model.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.minikun.model.ModelProviderException;

class FailoverTaskModelProviderTest {
    private static final TaskModelRequest REQUEST = new TaskModelRequest(
            List.of(new TaskModelMessage("user", "Summarize this")),
            64, 0.0, TaskModelRequest.ResponseFormat.TEXT);

    @Test
    void successfulPrimaryDoesNotCallFallback() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        TaskModelProvider provider = new FailoverTaskModelProvider(
                request -> "primary",
                request -> {
                    fallbackCalls.incrementAndGet();
                    return "fallback";
                }, Duration.ofSeconds(30));

        assertEquals("primary", provider.generate(REQUEST));
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    void primaryFailureFallsBackAndOpensCircuitDuringCooldown() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        AtomicLong now = new AtomicLong(10L);
        TaskModelProvider provider = new FailoverTaskModelProvider(
                request -> {
                    primaryCalls.incrementAndGet();
                    throw retryable("tinygrad unavailable");
                },
                request -> {
                    fallbackCalls.incrementAndGet();
                    return "ollama";
                }, Duration.ofNanos(100), now::get);

        assertEquals("ollama", provider.generate(REQUEST));
        now.set(50L);
        assertEquals("ollama", provider.generate(REQUEST));
        assertEquals(1, primaryCalls.get());
        assertEquals(2, fallbackCalls.get());
    }

    @Test
    void retriesPrimaryAfterCooldownAndClosesCircuitOnSuccess() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicLong now = new AtomicLong(10L);
        TaskModelProvider provider = new FailoverTaskModelProvider(
                request -> primaryCalls.incrementAndGet() == 1
                        ? retryableFailure("temporary failure") : "recovered",
                request -> "ollama", Duration.ofNanos(100), now::get);

        assertEquals("ollama", provider.generate(REQUEST));
        now.set(110L);
        assertEquals("recovered", provider.generate(REQUEST));
        now.set(111L);
        assertEquals("recovered", provider.generate(REQUEST));
        assertEquals(3, primaryCalls.get());
    }

    @Test
    void preservesPrimaryFailureWhenFallbackAlsoFails() {
        TaskModelProvider provider = new FailoverTaskModelProvider(
                request -> retryableFailure("tinygrad failed"),
                request -> fail("ollama failed"), Duration.ofSeconds(30));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> provider.generate(REQUEST));

        assertEquals("ollama failed", failure.getMessage());
        assertEquals("tinygrad failed", failure.getSuppressed()[0].getMessage());
    }

    @Test
    void nonRetryablePrimaryFailureIsNotMasked() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        TaskModelProvider provider = new FailoverTaskModelProvider(
                request -> {
                    throw new ModelProviderException("bad request", false);
                }, request -> {
                    fallbackCalls.incrementAndGet();
                    return "fallback";
                }, Duration.ofSeconds(30));

        ModelProviderException failure = assertThrows(
                ModelProviderException.class, () -> provider.generate(REQUEST));

        assertEquals("bad request", failure.getMessage());
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    void negativeNanoTimeDoesNotOpenAnUninitializedCircuit() {
        TaskModelProvider provider = new FailoverTaskModelProvider(
                request -> "primary", request -> "fallback",
                Duration.ofSeconds(30), () -> Long.MIN_VALUE + 1L);

        assertEquals("primary", provider.generate(REQUEST));
    }

    @Test
    void onlyOneRequestProbesPrimaryWhenCooldownExpires() throws Exception {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicLong now = new AtomicLong(10L);
        CountDownLatch probeEntered = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        TaskModelProvider provider = new FailoverTaskModelProvider(request -> {
            if (primaryCalls.incrementAndGet() == 1) {
                throw retryable("temporary failure");
            }
            probeEntered.countDown();
            try {
                if (!releaseProbe.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("probe was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("probe interrupted", exception);
            }
            return "recovered";
        }, request -> "ollama", Duration.ofNanos(100), now::get);

        assertEquals("ollama", provider.generate(REQUEST));
        now.set(110L);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var probe = executor.submit(() -> provider.generate(REQUEST));
            if (!probeEntered.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("probe did not start");
            }
            assertEquals("ollama", provider.generate(REQUEST));
            releaseProbe.countDown();
            assertEquals("recovered", probe.get(2, TimeUnit.SECONDS));
        }
        assertEquals(2, primaryCalls.get());
    }

    @Test
    void rejectsNonPositiveCooldown() {
        assertThrows(IllegalArgumentException.class, () -> new FailoverTaskModelProvider(
                request -> "primary", request -> "fallback", Duration.ZERO));
    }

    private static String fail(String message) {
        throw new IllegalStateException(message);
    }

    private static ModelProviderException retryable(String message) {
        return new ModelProviderException(message, true);
    }

    private static String retryableFailure(String message) {
        throw retryable(message);
    }
}
