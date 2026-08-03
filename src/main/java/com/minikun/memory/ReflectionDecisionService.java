package com.minikun.memory;

import java.util.List;

import com.minikun.memory.model.AcceptedMemory;
import com.minikun.memory.model.MemoryCandidate;
import com.minikun.memory.model.MemorySource;

public final class ReflectionDecisionService {
    private final MemorySource source;

    public ReflectionDecisionService() {
        this(MemorySource.LLM_EXTRACTION);
    }

    public ReflectionDecisionService(MemorySource source) {
        this.source = java.util.Objects.requireNonNull(source, "source must not be null");
    }

    public List<AcceptedMemory> decide(List<MemoryCandidate> candidates) {
        java.util.Objects.requireNonNull(candidates, "candidates must not be null");
        return candidates.stream()
                .filter(this::valid)
                .map(candidate -> new AcceptedMemory(
                        candidate.conversationId(), candidate.category(), source,
                        candidate.content(), candidate.confidence(), candidate.reason()))
                .toList();
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