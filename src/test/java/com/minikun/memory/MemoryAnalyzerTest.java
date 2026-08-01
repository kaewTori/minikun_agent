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
        void rejectsQuestionsAndUncontextualizedSingleWordCandidates() {
        CompletedConversation conversation = new CompletedConversation(
            "conversation-questions", List.of(
                new CompletedConversation.Message("user", "mac"),
                new CompletedConversation.Message("user", "โปรเจคที่เราทำอยู่คือโปรเจคอะไรหรอ?")));
        MemoryExtractionClient client = ignored -> List.of(
            new CandidateMemory(MemoryCategory.PROFILE, "mac", 0.9, "user stated directly"),
            new CandidateMemory(MemoryCategory.PROJECT, "โปรเจคที่เราทำอยู่คือโปรเจคอะไรหรอ?", 0.9,
                "user stated directly"));

        assertEquals(List.of(), new MemoryAnalyzer(client, MemoryPolicy.defaults()).analyze(conversation));
        }

        @Test
        void acceptsShortCandidateWhenAnotherUserMessageProvidesContext() {
        CompletedConversation conversation = new CompletedConversation(
            "conversation-context", List.of(
                new CompletedConversation.Message("user", "mac"),
                new CompletedConversation.Message("user", "ฉันใช้ mac เป็นเครื่องหลัก")));
        MemoryExtractionClient client = ignored -> List.of(
            new CandidateMemory(MemoryCategory.PROFILE, "mac", 0.9, "user uses mac"));

        assertEquals(List.of(new CandidateMemory(MemoryCategory.PROFILE, "mac", 0.9, "user uses mac")),
            new MemoryAnalyzer(client, MemoryPolicy.defaults()).analyze(conversation));
        }

    @Test
    void propagatesExtractionFailure() {
        MemoryException failure = new MemoryException("llm unavailable");
        MemoryAnalyzer analyzer = new MemoryAnalyzer(conversation -> { throw failure; }, MemoryPolicy.defaults());

        assertThrows(MemoryException.class, () -> analyzer.analyze(CONVERSATION));
    }
}
