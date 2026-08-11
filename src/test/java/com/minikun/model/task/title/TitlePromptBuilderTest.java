package com.minikun.model.task.title;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.conversation.ChatMessage;

class TitlePromptBuilderTest {
    private final TitlePromptBuilder builder = new TitlePromptBuilder();

    @Test
    void includesOnlyLatestUserAndAssistantMessagesAndIndependentRules() {
        String prompt = builder.build(List.of(
                new ChatMessage("user", "earlier question"),
                new ChatMessage("assistant", "earlier answer"),
                new ChatMessage("user", "How do I configure PostgreSQL?"),
                new ChatMessage("assistant", "Use the datasource properties.")));

        assertTrue(prompt.contains("user: How do I configure PostgreSQL?"));
        assertTrue(prompt.contains("assistant: Use the datasource properties."));
        assertFalse(prompt.contains("earlier question"));
        assertTrue(prompt.contains("3-8 words"));
        assertTrue(prompt.contains("same language"));
        assertTrue(prompt.contains("no quotation marks"));
        assertFalse(prompt.contains("Minikun"));
        assertFalse(prompt.contains("MCS"));
        assertFalse(prompt.contains("persona"));
    }

    @Test
    void boundsLargeConversationContext() {
        String prompt = builder.build(List.of(
                new ChatMessage("user", "x".repeat(4_000))));

        assertTrue(prompt.length() < 2_300);
    }
}
