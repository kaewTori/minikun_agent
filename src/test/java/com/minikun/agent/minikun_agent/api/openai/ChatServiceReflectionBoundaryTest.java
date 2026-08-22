package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.agent.minikun_agent.conversation.ConversationMemoryService;
import com.minikun.memory.model.CompletedConversation;

class ChatServiceReflectionBoundaryTest {
    private static final ConversationId CONVERSATION_ID = new ConversationId("conversation-1");

    @Test
    void selectsLatestCompletedTurnAndPreservesConsecutiveUsers() throws Exception {
        List<ChatMessage> messages = List.of(
                new ChatMessage("user", "old user"),
                new ChatMessage("assistant", "old assistant"),
                new ChatMessage("user", "latest user A"),
                new ChatMessage("user", "latest user B"),
                new ChatMessage("assistant", "latest assistant"));
        ConversationMemoryService memory = mockMemory(messages);

        Optional<CompletedConversation> snapshot = select(memory);

        assertEquals(List.of(
                new CompletedConversation.Message("user", "latest user A"),
                new CompletedConversation.Message("user", "latest user B"),
                new CompletedConversation.Message("assistant", "latest assistant")),
                snapshot.orElseThrow().messages());
    }

    @Test
    void skipsPartialLatestTurnInsteadOfUsingHistoricalAssistant() throws Exception {
        ConversationMemoryService memory = mockMemory(List.of(
                new ChatMessage("user", "old user"),
                new ChatMessage("assistant", "old assistant"),
                new ChatMessage("user", "partial latest user")));

        assertTrue(select(memory).isEmpty());
    }

    @Test
    void excludesSystemMessagesAndDoesNotCrossSystemBoundary() throws Exception {
        ConversationMemoryService memory = mockMemory(List.of(
                new ChatMessage("user", "old user"),
                new ChatMessage("assistant", "old assistant"),
                new ChatMessage("system", "system boundary"),
                new ChatMessage("user", "latest user"),
                new ChatMessage("assistant", "latest assistant")));

        Optional<CompletedConversation> snapshot = select(memory);

        assertEquals(List.of(
                new CompletedConversation.Message("user", "latest user"),
                new CompletedConversation.Message("assistant", "latest assistant")),
                snapshot.orElseThrow().messages());
    }

    @Test
    void skipsEmptyAssistantOnlyAndUserOnlyHistories() throws Exception {
        assertTrue(select(mockMemory(List.of())).isEmpty());
        assertTrue(select(mockMemory(List.of(new ChatMessage("assistant", "assistant")))).isEmpty());
        assertTrue(select(mockMemory(List.of(new ChatMessage("user", "user")))).isEmpty());
        assertTrue(select(mockMemory(List.of(new ChatMessage("system", "system")))).isEmpty());
    }

    @Test
    void snapshotIsImmutableAndRepeatedSelectionIsDeterministic() throws Exception {
        List<ChatMessage> messages = new java.util.ArrayList<>(List.of(
                new ChatMessage("user", "user"),
                new ChatMessage("assistant", "assistant")));
        ConversationMemoryService memory = mockMemory(messages);

        Optional<CompletedConversation> first = select(memory);
        messages.set(0, new ChatMessage("user", "changed source"));
        Optional<CompletedConversation> second = select(mockMemory(List.of(
                new ChatMessage("user", "user"),
                new ChatMessage("assistant", "assistant"))));

        assertEquals(first, second);
        assertEquals("user", first.orElseThrow().messages().getFirst().content());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> first.orElseThrow().messages().add(null));
    }

    private ConversationMemoryService mockMemory(List<ChatMessage> messages) {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.load(CONVERSATION_ID)).thenReturn(messages);
        return memory;
    }

    private Optional<CompletedConversation> select(ConversationMemoryService memory) {
        ChatTurnFinalizer finalizer = new ChatTurnFinalizer(
                memory, mock(ObjectProvider.class), null, null, null, false);
        return finalizer.completedConversation("owner-1", CONVERSATION_ID);
    }
}
