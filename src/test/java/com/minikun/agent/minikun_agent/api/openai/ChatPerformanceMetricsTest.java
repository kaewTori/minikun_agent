package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ChatPerformanceMetricsTest {
    @Test
    void recordsStageLatencyAndFastPathCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatPerformanceMetrics metrics = new ChatPerformanceMetrics(registry);

        metrics.record("search", System.nanoTime() - 1_000_000, "skipped");
        metrics.fastPath("no_search");
        metrics.generationProfile("companion", 384);
        metrics.modelUsage(120, 30, 2_000_000_000L);

        assertEquals(1L, registry.get(ChatPerformanceMetrics.STAGE_DURATION)
                .tags("stage", "search", "result", "skipped").timer().count());
        assertNotNull(registry.get(ChatPerformanceMetrics.STAGE_DURATION)
                .tags("stage", "search", "result", "skipped").timer()
                .takeSnapshot().percentileValues());
        assertEquals(1.0, registry.get(ChatPerformanceMetrics.FAST_PATH_REQUESTS)
                .tag("reason", "no_search").counter().count());
        assertEquals(1.0, registry.get(ChatPerformanceMetrics.GENERATION_PROFILES)
                .tag("profile", "companion").counter().count());
        assertEquals(15.0, registry.get(ChatPerformanceMetrics.TOKENS_PER_SECOND)
                .summary().mean());
    }
}
