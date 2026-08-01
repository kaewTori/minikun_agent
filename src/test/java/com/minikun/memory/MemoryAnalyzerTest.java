package com.minikun.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.minikun.memory.model.CandidateMemory;
import com.minikun.memory.model.CompletedConversation;
import com.minikun.memory.model.MemoryCategory;

class MemoryAnalyzerTest {
    private static final CompletedConversation CONVERSATION = new CompletedConversation(
            "conversation-1", List.of(new CompletedConversation.Message("user", "I use Vim.")));

    @Test
    void appliesConfigurablePolicyAndNormalizedDuplicateRule() {
        MemoryExtractionClient client = conversation -> List.of(
                new CandidateMemory(MemoryCategory.PREFERENCE, "Editor: Vim", 0.8, "explicit preference"),
                new CandidateMemory(MemoryCategory.PREFERENCE, " editor:   vim ", 0.9, "duplicate"),
                new CandidateMemory(MemoryCategory.GOAL, "Finish this week", 0.99, "temporary"),
                new CandidateMemory(MemoryCategory.SKILL, "Java", 0.6, "low confidence"));

        var result = new MemoryAnalyzer(client, new MemoryPolicy(0.7, 100)).analyze(CONVERSATION);

        assertEquals(List.of(new CandidateMemory(MemoryCategory.PREFERENCE, "Editor: Vim", 0.8,
                "explicit preference")), result);
    }

    @Test
    void propagatesExtractionFailure() {
        MemoryException failure = new MemoryException("llm unavailable");
        MemoryAnalyzer analyzer = new MemoryAnalyzer(conversation -> { throw failure; }, MemoryPolicy.defaults());

        assertThrows(MemoryException.class, () -> analyzer.analyze(CONVERSATION));
    }
}
