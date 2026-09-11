package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OptionalContextBudgetTest {
    @Test
    void slowContextTimesOutWithoutDiscardingReadyContextAndWorkerIsInterrupted() throws Exception {
        var registry = new SimpleMeterRegistry();
        var metrics = new ChatPerformanceMetrics(registry);
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var slow = OptionalContextBudget.start(() -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return "late";
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        var ready = java.util.concurrent.CompletableFuture.completedFuture("local evidence");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(30);
        assertEquals("", OptionalContextBudget.await(slow, deadline, "", "memory_wait", metrics));
        assertEquals("local evidence", OptionalContextBudget.await(ready, deadline, "", "personal_wait", metrics));
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertEquals(1, registry.get(ChatPerformanceMetrics.STAGE_DURATION)
                .tags("stage", "memory_wait", "result", "timeout").timer().count());
    }

    @Test
    void failureUsesFallbackAndIsCountedAsFailureRatherThanSuccess() {
        var registry = new SimpleMeterRegistry();
        var failed = java.util.concurrent.CompletableFuture.<String>failedFuture(new IllegalStateException("offline"));
        assertEquals("", OptionalContextBudget.await(failed, System.nanoTime(), "", "summary_wait",
                new ChatPerformanceMetrics(registry)));
        assertEquals(1, registry.get(ChatPerformanceMetrics.STAGE_DURATION)
                .tags("stage", "summary_wait", "result", "fallback").timer().count());
    }
}
