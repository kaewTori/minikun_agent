package com.minikun.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.minikun.memory.model.Memory;
import com.minikun.memory.model.AcceptedMemory;
import com.minikun.memory.model.MemoryCategory;
import com.minikun.memory.model.MemoryId;
import com.minikun.memory.model.MemorySource;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.agent.minikun_agent.conversation.ConversationId;

class MemoryRecallServiceTest {
    @Test
    void zeroLimitReturnsEmptyWithoutRepositoryAccess() {
        AtomicBoolean accessed = new AtomicBoolean();
        MemoryRepository repository = new MemoryRepository() {
            @Override
            public boolean save(AcceptedMemory value) {
                return true;
            }

            @Override
            public List<Memory> find(MemoryScope scope, int limit) {
                accessed.set(true);
                return List.of();
            }
        };
        MemoryRecallService service = new MemoryRecallService(repository,
                memories -> new KnowledgeContext("unexpected"));

        assertEquals("", service.recall(
                new MemoryScope("owner-a", new ConversationId("conversation-a")), 0).content());
        assertEquals(false, accessed.get());
    }

    @Test
    void rejectsNegativeLimit() {
        MemoryRecallService service = new MemoryRecallService(new MemoryRepository() {
            @Override
            public boolean save(AcceptedMemory value) {
                return true;
            }

            @Override
            public List<Memory> find(MemoryScope scope, int limit) {
                return List.of();
            }
        },
                memories -> new KnowledgeContext(""));

        assertThrows(IllegalArgumentException.class, () -> service.recall(
                new MemoryScope("owner-a", new ConversationId("conversation-a")), -1));
    }

    @Test
    void recallsRepositoryMemoriesThroughTheConfiguredPipeline() {
        Memory memory = new Memory(
                new MemoryId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                MemoryCategory.PROFILE,
                MemorySource.LLM_EXTRACTION,
                "Lives in Bangkok",
                Instant.parse("2026-08-01T00:00:00Z"),
                0.95,
                "user stated directly");
        AtomicReference<MemoryScope> requestedScope = new AtomicReference<>();
        AtomicReference<Integer> requestedLimit = new AtomicReference<>();
        MemoryRepository repository = new MemoryRepository() {
            @Override
            public boolean save(AcceptedMemory value) {
                return true;
            }

            @Override
            public List<Memory> find(MemoryScope scope, int limit) {
                requestedScope.set(scope);
                requestedLimit.set(limit);
                return List.of(memory);
            }
        };

        MemoryRecallService service = new MemoryRecallService(
                repository,
                memories -> new KnowledgeContext(memories.getFirst().content()));

        assertEquals("Lives in Bangkok", service.recall(
            new MemoryScope("owner-1", new ConversationId("conversation-1")), 1).content());
        assertEquals(new MemoryScope("owner-1", new ConversationId("conversation-1")),
            requestedScope.get());
        assertEquals(1, requestedLimit.get());
    }
}