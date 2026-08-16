package com.minikun.memory.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.minikun.memory.model.Memory;
import com.minikun.memory.model.MemoryCategory;
import com.minikun.memory.model.MemoryId;
import com.minikun.memory.model.MemorySource;

class MemoryRecallComponentsTest {
    @Test
    void selectorOrdersAllCategoriesAndAppliesMaximumCount() {
        Instant sameTime = Instant.parse("2026-08-01T00:00:00Z");
        Memory older = memory("00000000-0000-0000-0000-000000000003", MemoryCategory.PROFILE,
                "older", sameTime.minusSeconds(1));
        Memory tieHigherId = memory("00000000-0000-0000-0000-000000000002", MemoryCategory.GOAL,
                "tie higher", sameTime);
        Memory tieLowerId = memory("00000000-0000-0000-0000-000000000001", MemoryCategory.PREFERENCE,
                "tie lower", sameTime);

        List<Memory> selected = new MemorySelector(2).select(List.of(older, tieHigherId, tieLowerId));

        assertEquals(List.of(tieLowerId, tieHigherId), selected);
    }

    @Test
    void formatterReturnsEmptyAndBoundedKnowledgeContext() {
        Memory first = memory("00000000-0000-0000-0000-000000000001", MemoryCategory.PROFILE,
                "Lives in Bangkok", Instant.now());
        Memory second = memory("00000000-0000-0000-0000-000000000002", MemoryCategory.GOAL,
                "Learn Java", Instant.now());

        assertEquals("", new MemoryFormatter(20).format(List.of()).content());
        assertEquals("PROFILE: Lives in Bangkok (confidence=0.9, source=LLM_EXTRACTION, reason=user stated directly)", new MemoryFormatter(100)
                .format(List.of(first, second)).content());
    }

    private Memory memory(String id, MemoryCategory category, String content, Instant createdAt) {
        return new Memory(new MemoryId(UUID.fromString(id)), category, MemorySource.LLM_EXTRACTION,
                content, createdAt, 0.9, "user stated directly");
    }
}
