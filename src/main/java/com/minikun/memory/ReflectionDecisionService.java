package com.minikun.memory;

import java.util.List;

import com.minikun.memory.model.AcceptedMemory;
import com.minikun.memory.model.MemoryCandidate;
import com.minikun.memory.model.MemorySource;

public final class ReflectionDecisionService {
    public List<AcceptedMemory> decide(List<MemoryCandidate> candidates) {
        java.util.Objects.requireNonNull(candidates, "candidates must not be null");
        return candidates.stream()
                .filter(this::valid)
                .map(candidate -> new AcceptedMemory(
                        candidate.conversationId(), candidate.category(), candidate.content(),
                    candidate.confidence(), candidate.reason(), MemorySource.LLM_EXTRACTION))
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