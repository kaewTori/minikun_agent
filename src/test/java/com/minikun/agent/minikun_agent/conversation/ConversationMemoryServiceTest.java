package com.minikun.agent.minikun_agent.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

class ConversationMemoryServiceTest {

    private final ConversationMemoryService service = new ConversationMemoryService(
            MessageWindowChatMemory.builder()
                    .chatMemoryRepository(new InMemoryChatMemoryRepository())
                    .maxMessages(4)
                    .build());

    @Test
    void appendsAndLoadsMessagesWithoutExposingSpringAiTypes() {
        ConversationId conversationId = new ConversationId("conversation-1");

        service.append(conversationId, new ChatMessage("user", "Hello"));
        service.append(conversationId, new ChatMessage("assistant", "Hi"));

        assertEquals(List.of(
                new ChatMessage("user", "Hello"),
                new ChatMessage("assistant", "Hi")), service.load(conversationId));
    }

    @Test
    void appendsACompletedTurnInOneMemoryOperation() {
        ConversationId conversationId = new ConversationId("conversation-turn");

        service.appendTurn(conversationId,
                new ChatMessage("user", "Question"),
                new ChatMessage("assistant", "Answer"));

        assertEquals(List.of(
                new ChatMessage("user", "Question"),
                new ChatMessage("assistant", "Answer")), service.load(conversationId));
    }

    @Test
    void conversationsRemainIsolatedAndCanBeCleared() {
        ConversationId first = new ConversationId("conversation-1");
        ConversationId second = new ConversationId("conversation-2");

        service.append(first, new ChatMessage("user", "First"));
        service.append(second, new ChatMessage("user", "Second"));
        service.clear(first);

        assertTrue(service.load(first).isEmpty());
        assertEquals(List.of(new ChatMessage("user", "Second")), service.load(second));
    }
}
