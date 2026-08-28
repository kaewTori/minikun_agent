package com.minikun.memory;

import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.embedding.EmbeddingModel;

import com.minikun.memory.model.Memory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Semantic memory ranking with cached document embeddings and lexical fallback. */
public final class EmbeddingMemoryRelevanceRanker implements MemoryRanker {
    private final EmbeddingModel embeddingModel;
    private final double semanticWeight;
    private final Map<String, float[]> memoryEmbeddings;
    private final Counter semanticSuccess;
    private final Counter semanticFallback;

    public EmbeddingMemoryRelevanceRanker(
            EmbeddingModel embeddingModel,
            double semanticWeight,
            MeterRegistry meterRegistry) {
        this(embeddingModel, semanticWeight, meterRegistry, 1_000);
    }

    public EmbeddingMemoryRelevanceRanker(
            EmbeddingModel embeddingModel,
            double semanticWeight,
            MeterRegistry meterRegistry,
            int maximumCacheEntries) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embedding model must not be null");
        if (semanticWeight < 0 || semanticWeight > 1) {
            throw new IllegalArgumentException("semantic memory weight must be between 0 and 1");
        }
        if (maximumCacheEntries < 1) {
            throw new IllegalArgumentException("semantic memory cache size must be positive");
        }
        this.semanticWeight = semanticWeight;
        this.memoryEmbeddings = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                return size() > maximumCacheEntries;
            }
        });
        this.semanticSuccess = Counter.builder("minikun.memory.semantic.rankings")
                .tag("status", "success").register(meterRegistry);
        this.semanticFallback = Counter.builder("minikun.memory.semantic.rankings")
                .tag("status", "fallback").register(meterRegistry);
    }

    @Override
    public List<Memory> rank(List<Memory> memories, String query, int limit) {
        if (memories == null || memories.isEmpty() || limit == 0) return List.of();
        if (query == null || query.isBlank()) return MemoryRelevanceRanker.rank(memories, query, limit);
        try {
            float[] queryEmbedding = embeddingModel.embed(query);
            List<ScoredMemory> scored = memories.stream()
                    .map(memory -> new ScoredMemory(memory, blendedScore(memory, queryEmbedding)))
                    .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed()
                            .thenComparing(item -> item.memory().createdAt(), Comparator.reverseOrder()))
                    .limit(limit)
                    .toList();
            semanticSuccess.increment();
            return scored.stream().map(ScoredMemory::memory).toList();
        } catch (RuntimeException exception) {
            semanticFallback.increment();
            return MemoryRelevanceRanker.rank(memories, query, limit);
        }
    }

    private double blendedScore(Memory memory, float[] queryEmbedding) {
        String key = memory.id().value() + ":" + Integer.toHexString(memory.content().hashCode());
        float[] memoryEmbedding;
        synchronized (memoryEmbeddings) {
            memoryEmbedding = memoryEmbeddings.get(key);
        }
        if (memoryEmbedding == null) {
            float[] created = embeddingModel.embed(memory.category().name() + ": " + memory.content());
            synchronized (memoryEmbeddings) {
                float[] existing = memoryEmbeddings.get(key);
                memoryEmbedding = existing == null ? created : existing;
                if (existing == null) memoryEmbeddings.put(key, created);
            }
        }
        double semantic = (cosine(queryEmbedding, memoryEmbedding) + 1.0) / 2.0;
        return semantic * semanticWeight + memory.confidence() * (1.0 - semanticWeight);
    }

    private double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            throw new IllegalArgumentException("embedding vectors must have the same non-zero dimensions");
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) throw new IllegalArgumentException("embedding vector must not be zero");
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private record ScoredMemory(Memory memory, double score) {}
}
