package com.minikun.memory;

import java.util.List;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import com.minikun.memory.model.AcceptedMemory;
import com.minikun.memory.model.MemoryCandidate;
import com.minikun.memory.model.MemorySource;

public final class ReflectionDecisionService {
    private static final String ACCEPTED = "minikun.memory.reflection.candidates.accepted";
    private static final String REJECTED = "minikun.memory.reflection.candidates.rejected";
    private final MeterRegistry meterRegistry;

    public ReflectionDecisionService() {
        this(null);
    }

    public ReflectionDecisionService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public List<AcceptedMemory> decide(List<MemoryCandidate> candidates) {
        java.util.Objects.requireNonNull(candidates, "candidates must not be null");
        List<AcceptedMemory> accepted = candidates.stream()
                .filter(this::valid)
                .map(candidate -> new AcceptedMemory(
                        candidate.conversationId(), candidate.category(), candidate.content(),
                    candidate.confidence(), candidate.reason(), MemorySource.LLM_EXTRACTION))
                .toList();
        increment(ACCEPTED, accepted.size());
        increment(REJECTED, candidates.size() - accepted.size());
        return accepted;
    }

    private void increment(String name, int count) {
        try {
            Counter.builder(name).register(meterRegistry).increment(count);
        } catch (RuntimeException ignored) {
        }
    }

    private boolean valid(MemoryCandidate candidate) {
        return candidate != null
                && candidate.conversationId() != null
                && !candidate.conversationId().isBlank()
                && candidate.category() != null
                && candidate.content() != null
                && !candidate.content().isBlank()
                && Double.isFinite(candidate.confidence())
                && candidate.confidence() >= 0.0
                && candidate.confidence() <= 1.0
                && candidate.reason() != null
                && !candidate.reason().isBlank();
    }
}