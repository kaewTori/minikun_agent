package com.minikun.agent.minikun_agent.api.openai;

import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** One deadline for optional context; blocking I/O never occupies the common pool. */
final class OptionalContextBudget {
    private OptionalContextBudget() { }

    static <T> Future<T> start(Supplier<T> supplier) {
        FutureTask<T> task = new FutureTask<>(supplier::get);
        Thread.ofVirtual().name("minikun-optional-context").start(task);
        return task;
    }

    static <T> T await(Future<T> task, long deadline, T fallback,
            String stage, ChatPerformanceMetrics metrics) {
        long started = System.nanoTime();
        String result = "success";
        try {
            return task.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            result = "timeout";
            task.cancel(true);
            return fallback;
        } catch (InterruptedException exception) {
            result = "cancelled";
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("chat interrupted while waiting for context", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            result = "fallback";
            return fallback;
        } finally {
            if (metrics != null) metrics.record(stage, started, result);
        }
    }
}
