package com.minikun.visual;

import java.time.Instant;

/** Reads the small, sanitized runtime snapshot shown by the Image Studio. */
@FunctionalInterface
public interface TinyGradRuntimeStatusReader {
    RuntimeStatus read();

    record RuntimeMemory(long active_bytes, long cached_bytes, long tracked_bytes) { }

    record RuntimeStatus(
            boolean online,
            String status,
            String endpoint,
            long latency_ms,
            RuntimeMemory memory,
            long vram_budget_bytes,
            double vram_used_percent,
            long request_count,
            long recycle_requests,
            boolean recycle_requested,
            String recycle_reason,
            String last_resolution,
            Integer last_prompt_chunks,
            int compiler_processes,
            int compile_workers,
            Instant checked_at) { }
}
