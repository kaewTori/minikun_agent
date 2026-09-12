package com.minikun.agent.minikun_agent.api.openai;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Small request-scoped budget carried through foreground and background chat work. */
record ChatRequestContext(String jobId, String traceId, Instant deadline) {
    ChatRequestContext {
        jobId = Objects.requireNonNullElse(jobId, "").strip();
        traceId = Objects.requireNonNullElse(traceId, "").strip();
        deadline = Objects.requireNonNullElse(deadline, Instant.MAX);
    }

    static ChatRequestContext direct() {
        return new ChatRequestContext("", "", Instant.MAX);
    }

    static ChatRequestContext background(UUID jobId, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        return new ChatRequestContext(
                jobId == null ? "" : jobId.toString(),
                jobId == null ? "" : jobId.toString(),
                Instant.now().plus(timeout));
    }

    boolean expired() {
        return !Instant.MAX.equals(deadline) && !Instant.now().isBefore(deadline);
    }

    long deadlineNanos(Duration fallback) {
        long now = System.nanoTime();
        long fallbackNanos = fallback == null ? Long.MAX_VALUE : Math.max(1L, fallback.toNanos());
        if (Instant.MAX.equals(deadline)) {
            return saturatingAdd(now, fallbackNanos);
        }
        long remaining;
        try {
            remaining = Math.max(1L, Duration.between(Instant.now(), deadline).toNanos());
        } catch (ArithmeticException overflow) {
            remaining = Long.MAX_VALUE;
        }
        return saturatingAdd(now, Math.min(fallbackNanos, remaining));
    }

    void requireRemaining(String stage) {
        if (expired()) {
            throw new ChatDeadlineExceededException(stage);
        }
    }

    private long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
