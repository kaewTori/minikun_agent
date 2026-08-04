package com.minikun.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.minikun.memory.model.AcceptedMemory;
import com.minikun.memory.model.MemoryCategory;
import com.minikun.memory.model.MemoryCandidate;
import com.minikun.memory.model.MemorySource;

class ReflectionDecisionServiceTest {
    private final ReflectionDecisionService service = new ReflectionDecisionService();

    @Test
    void acceptsValidCandidatesInOriginalOrder() {
        MemoryCandidate first = candidate(MemoryCategory.PROFILE, "Uses macOS", 0.9);
        MemoryCandidate second = candidate(MemoryCategory.SKILL, "Writes Java", 0.8);

        List<AcceptedMemory> accepted = service.decide(List.of(first, second));

        assertEquals(List.of(
            new AcceptedMemory("conversation-1", MemoryCategory.PROFILE,
                "Uses macOS", 0.9, "stated", MemorySource.LLM_EXTRACTION),
            new AcceptedMemory("conversation-1", MemoryCategory.SKILL,
                "Writes Java", 0.8, "stated", MemorySource.LLM_EXTRACTION)), accepted);
    }

    @Test
    void rejectsEmptyContentAndInvalidConfidence() {
        MemoryCandidate empty = candidate(MemoryCategory.PROFILE, "   ", 0.9);
        MemoryCandidate tooHigh = candidate(MemoryCategory.SKILL, "Java", 1.1);
        MemoryCandidate notFinite = candidate(MemoryCategory.GOAL, "Ship it", Double.NaN);

        assertEquals(List.of(), service.decide(List.of(empty, tooHigh, notFinite)));
    }

    @Test
    void preservesConversationContext() {
        MemoryCandidate candidate = new MemoryCandidate(
                "conversation-with-context", MemoryCategory.PROJECT, "Build memory", 1.0, "stated");

        assertEquals("conversation-with-context", service.decide(List.of(candidate)).getFirst().conversationId());
    }

    @Test
    void supportsZeroCandidatesAndNeverIncreasesCardinality() {
        MemoryCandidate valid = candidate(MemoryCategory.PROFILE, "Uses macOS", 0.9);
        MemoryCandidate rejected = candidate(MemoryCategory.PROFILE, "", 0.9);

        assertEquals(List.of(), service.decide(List.of()));
        assertEquals(1, service.decide(List.of(valid, rejected)).size());
    }

    @Test
    void isDeterministicAndReturnsImmutableOutput() {
        List<MemoryCandidate> candidates = List.of(candidate(MemoryCategory.PROFILE, "Uses macOS", 0.9));

        List<AcceptedMemory> first = service.decide(candidates);
        List<AcceptedMemory> second = service.decide(candidates);

        assertEquals(first, second);
        assertThrows(UnsupportedOperationException.class, () -> first.add(
            new AcceptedMemory("conversation-1", MemoryCategory.SKILL,
                "Writes Java", 0.8, "stated", MemorySource.LLM_EXTRACTION)));
    }

    private MemoryCandidate candidate(MemoryCategory category, String content, double confidence) {
        return new MemoryCandidate("conversation-1", category, content, confidence, "stated");
    }
}
