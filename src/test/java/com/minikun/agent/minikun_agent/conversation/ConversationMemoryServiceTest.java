package com.minikun.agent.minikun_agent.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.health.contributor.Status;

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

    @Test
    void switchesToInMemoryAndStaysAvailableWhenPersistentMemoryFails() {
        ChatMemory persistent = mock(ChatMemory.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(persistent).add(anyString(), anyList());
        ConversationMemoryService degraded = new ConversationMemoryService(persistent, 4, true);
        ConversationId id = new ConversationId("database-offline");

        degraded.appendTurn(id, new ChatMessage("user", "ยังคุยได้ไหม"),
                new ChatMessage("assistant", "ได้ครับ"));
        degraded.append(id, new ChatMessage("user", "ต่อเลย"));

        assertEquals(3, degraded.load(id).size());
        assertTrue(degraded.degraded());
        verify(persistent, times(1)).add(anyString(), anyList());
        var health = new ConversationPersistenceHealthIndicator(degraded).health();
        assertEquals(new Status("DEGRADED"), health.getStatus());
        assertEquals("DEGRADED", health.getDetails().get("status"));
        assertEquals("in_memory", health.getDetails().get("mode"));
    }
}
