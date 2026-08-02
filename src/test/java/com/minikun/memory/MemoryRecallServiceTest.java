package com.minikun.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.minikun.memory.model.Memory;
import com.minikun.memory.model.MemoryCategory;
import com.minikun.memory.model.MemoryId;
import com.minikun.memory.model.MemorySource;
import com.minikun.pcs.model.KnowledgeContext;

class MemoryRecallServiceTest {
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
        MemoryRepository repository = new MemoryRepository() {
            @Override
            public boolean save(Memory value, String fingerprint, String conversationId) {
                return true;
            }

            @Override
            public List<Memory> findAll() {
                return List.of(memory);
            }
        };

        MemoryRecallService service = new MemoryRecallService(
                repository,
                memories -> new KnowledgeContext(memories.getFirst().content()));

        assertEquals("Lives in Bangkok", service.recall().content());
    }
}