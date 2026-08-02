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
        void rejectsConversationSpecificFoodPreferencesAndGenericProjectTopics() {
        CompletedConversation conversation = new CompletedConversation(
            "conversation-specific", List.of(
                new CompletedConversation.Message("user", "เราอยากกินราเมงแบบเข้ม ๆ ซุปทงคตสึ ไรงี้"),
                new CompletedConversation.Message("user", "ผู้ใช้กำลังทำโปรเจคเกี่ยวกับ AI")));
        MemoryExtractionClient client = ignored -> List.of(
            new CandidateMemory(MemoryCategory.PREFERENCE, "ผู้ใช้บอกว่าราคาไม่เกี่ยง บรรยากาศอะไรก็ได้", 0.9,
                "ผู้ใช้บอกว่าราคาไม่เกี่ยง บรรยากาศอะไรก็ได้"),
            new CandidateMemory(MemoryCategory.PREFERENCE, "ราเมงแบบเข้ม ๆ ซุปทงคตสึ", 0.8,
                "ผู้ใช้บอกว่าอยากกินราเมง"),
            new CandidateMemory(MemoryCategory.GOAL, "เราอยากกินราเมงแบบเข้ม ๆ ซุปทงคตสึ ไรงี้", 0.9,
                "ผู้ใช้บอกว่าอยากกินราเมง"),
            new CandidateMemory(MemoryCategory.PROJECT, "ผู้ใช้กำลังทำโปรเจคเกี่ยวกับ AI", 0.8,
                "ผู้ใช้กำลังทำโปรเจคเกี่ยวกับ AI"));

        assertEquals(List.of(), new MemoryAnalyzer(client, MemoryPolicy.defaults()).analyze(conversation));
        }

        @Test
        void acceptsSpecificDurableProject() {
        CompletedConversation conversation = new CompletedConversation(
            "conversation-project", List.of(
                new CompletedConversation.Message("user",
                    "ฉันกำลังทำโปรเจค Minikun Agent สำหรับระบบ long-term memory")));
        MemoryExtractionClient client = ignored -> List.of(
            new CandidateMemory(MemoryCategory.PROJECT,
                "กำลังทำโปรเจค Minikun Agent สำหรับระบบ long-term memory", 0.9,
                "ผู้ใช้ระบุชื่อและขอบเขตของโปรเจค"));

        assertEquals(1, new MemoryAnalyzer(client, MemoryPolicy.defaults()).analyze(conversation).size());
        }

    @Test
    void propagatesExtractionFailure() {
        MemoryException failure = new MemoryException("llm unavailable");
        MemoryAnalyzer analyzer = new MemoryAnalyzer(conversation -> { throw failure; }, MemoryPolicy.defaults());

        assertThrows(MemoryException.class, () -> analyzer.analyze(CONVERSATION));
    }
}
