package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import com.minikun.agent.minikun_agent.conversation.ChatMessage;

class ConversationHistoryWindowTest {
    private final ConversationHistoryWindow window = new ConversationHistoryWindow();

    @Test
    void visibleClientTranscriptWinsOverStoredHistory() {
        ChatCompletionRequest request = request(List.of(
                new Message("user", "คุยเรื่องสวน"),
                new Message("assistant", "เราเลือกปลูกมะลิ"),
                new Message("user", "แล้วต้องรดน้ำตอนไหน")));

        ConversationHistoryWindow.Result result = window.build(
                request,
                List.of(new ChatMessage("assistant", "stale server history")),
                ignored -> false,
                1_000);

        assertEquals(ConversationHistoryWindow.Source.CLIENT, result.source());
        assertTrue(result.content().contains("คุยเรื่องสวน"));
        assertTrue(result.content().contains("เราเลือกปลูกมะลิ"));
        assertFalse(result.content().contains("stale server history"));
    }

    @Test
    void longHistoryKeepsNewestMessagesWithinBudget() {
        List<ChatMessage> history = List.of(
                new ChatMessage("user", "oldest-" + "ก".repeat(100)),
                new ChatMessage("assistant", "old-answer-" + "ข".repeat(100)),
                new ChatMessage("user", "recent-question"),
                new ChatMessage("assistant", "latest-answer"));

        ConversationHistoryWindow.Result result = window.build(
                request(List.of(new Message("user", "ต่อจากเมื่อกี้"))),
                history,
                ignored -> false,
                120);

        assertEquals(ConversationHistoryWindow.Source.SERVER, result.source());
        assertTrue(result.content().length() <= 120);
        assertTrue(result.content().contains("latest-answer"));
        assertTrue(result.content().contains("recent-question"));
        assertTrue(result.content().contains("Earlier conversation omitted"));
        assertFalse(result.content().contains("oldest-"));
    }

    @Test
    void oversizedNewestMessageKeepsItsBeginningAndEnd() {
        String longAnswer = "important-start-" + "x".repeat(200) + "-important-end";

        ConversationHistoryWindow.Result result = window.build(
                request(List.of(new Message("user", "follow up"))),
                List.of(new ChatMessage("assistant", longAnswer)),
                ignored -> false,
                100);

        assertTrue(result.content().length() <= 100);
        assertTrue(result.content().contains("important-start"));
        assertTrue(result.content().contains("important-end"));
        assertTrue(result.content().contains("message shortened"));
    }

    @Test
    void rollingSummaryCanLimitVerbatimHistoryToNewestMessages() {
        List<ChatMessage> history = List.of(
                new ChatMessage("user", "old-question"),
                new ChatMessage("assistant", "old-answer"),
                new ChatMessage("user", "recent-question"),
                new ChatMessage("assistant", "recent-answer"));

        ConversationHistoryWindow.Result result = window.build(
                request(List.of(new Message("user", "continue"))), history,
                ignored -> false, 1_000, 2);

        assertEquals(4, result.inputMessages());
        assertEquals(2, result.selectedMessages());
        assertEquals(2, result.omittedMessages());
        assertFalse(result.content().contains("old-question"));
        assertTrue(result.content().contains("recent-question"));
        assertTrue(result.content().contains("recent-answer"));
    }

    @Test
    void ignoresLegacyTrailingUsersThatNeverReceivedAnAssistantResponse() {
        ConversationHistoryWindow.Result result = window.build(
                request(List.of(new Message("user", "current"))),
                List.of(
                        new ChatMessage("user", "completed question"),
                        new ChatMessage("assistant", "completed answer"),
                        new ChatMessage("user", "orphaned question")),
                ignored -> false,
                1_000);

        assertTrue(result.content().contains("completed answer"));
        assertFalse(result.content().contains("orphaned question"));
    }

    private ChatCompletionRequest request(List<Message> messages) {
        return new ChatCompletionRequest("mini-kun", messages, "conversation", false,
                null, null, null);
    }
}
