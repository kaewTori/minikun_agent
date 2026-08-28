package com.minikun.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import com.minikun.memory.model.Memory;
import com.minikun.memory.model.MemoryCategory;
import com.minikun.memory.model.MemoryId;
import com.minikun.memory.model.MemorySource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class EmbeddingMemoryRelevanceRankerTest {
    @Test
    void ranksByMeaningWhenWordsDoNotOverlap() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        Memory travel = memory("00000000-0000-0000-0000-000000000001", "ชอบเดินทางด้วยรถไฟ", 0.8);
        Memory food = memory("00000000-0000-0000-0000-000000000002", "แพ้อาหารทะเล", 0.9);
        when(model.embed("การคมนาคมที่พี่ชอบ")).thenReturn(new float[] {1, 0});
        when(model.embed("PREFERENCE: ชอบเดินทางด้วยรถไฟ")).thenReturn(new float[] {0.99f, 0.01f});
        when(model.embed("PREFERENCE: แพ้อาหารทะเล")).thenReturn(new float[] {0, 1});
        EmbeddingMemoryRelevanceRanker ranker = new EmbeddingMemoryRelevanceRanker(
                model, 0.9, new SimpleMeterRegistry());

        List<Memory> ranked = ranker.rank(List.of(food, travel), "การคมนาคมที่พี่ชอบ", 1);

        assertEquals(travel.id(), ranked.getFirst().id());
    }

    @Test
    void fallsBackToLexicalRankingWhenEmbeddingFails() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed("Bangkok home")).thenThrow(new IllegalStateException("offline"));
        Memory matching = memory("00000000-0000-0000-0000-000000000003", "Lives in Bangkok", 0.7);
        Memory other = memory("00000000-0000-0000-0000-000000000004", "Likes coffee", 0.9);
        EmbeddingMemoryRelevanceRanker ranker = new EmbeddingMemoryRelevanceRanker(
                model, 0.9, new SimpleMeterRegistry());

        assertEquals(matching.id(), ranker.rank(List.of(other, matching), "Bangkok home", 1).getFirst().id());
    }

    @Test
    void evictsLeastRecentlyUsedDocumentEmbeddingAtConfiguredLimit() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        Memory first = memory("00000000-0000-0000-0000-000000000005", "first", 0.8);
        Memory second = memory("00000000-0000-0000-0000-000000000006", "second", 0.8);
        when(model.embed("q1")).thenReturn(new float[] {1, 0});
        when(model.embed("q2")).thenReturn(new float[] {1, 0});
        when(model.embed("q3")).thenReturn(new float[] {1, 0});
        when(model.embed("PREFERENCE: first")).thenReturn(new float[] {1, 0});
        when(model.embed("PREFERENCE: second")).thenReturn(new float[] {1, 0});
        EmbeddingMemoryRelevanceRanker ranker = new EmbeddingMemoryRelevanceRanker(
                model, 0.9, new SimpleMeterRegistry(), 1);

        ranker.rank(List.of(first), "q1", 1);
        ranker.rank(List.of(second), "q2", 1);
        ranker.rank(List.of(first), "q3", 1);

        verify(model, times(2)).embed("PREFERENCE: first");
    }

    private Memory memory(String id, String content, double confidence) {
        return new Memory("owner", "conversation", new MemoryId(UUID.fromString(id)),
                MemoryCategory.PREFERENCE, MemorySource.LLM_EXTRACTION, content,
                Instant.parse("2026-08-01T00:00:00Z"), confidence, "test");
    }
}
