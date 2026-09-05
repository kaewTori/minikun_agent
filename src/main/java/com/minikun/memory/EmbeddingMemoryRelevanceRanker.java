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
            Map<String, float[]> embeddings = embeddingsFor(memories);
            List<ScoredMemory> scored = memories.stream()
                    .map(memory -> new ScoredMemory(
                            memory, blendedScore(memory, queryEmbedding, embeddings.get(key(memory)))))
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

    private Map<String, float[]> embeddingsFor(List<Memory> memories) {
        Map<String, float[]> result = new LinkedHashMap<>();
        Map<String, Memory> missing = new LinkedHashMap<>();
        synchronized (memoryEmbeddings) {
            for (Memory memory : memories) {
                String key = key(memory);
                float[] cached = memoryEmbeddings.get(key);
                if (cached == null) missing.putIfAbsent(key, memory);
                else result.put(key, cached);
            }
        }
        if (!missing.isEmpty()) {
            List<float[]> created = embeddingModel.embed(missing.values().stream()
                    .map(this::embeddingText)
                    .toList());
            if (created == null || created.size() != missing.size()) {
                throw new IllegalStateException("embedding batch size must match memory batch size");
            }
            int index = 0;
            for (String key : missing.keySet()) {
                float[] embedding = created.get(index++);
                result.put(key, embedding);
                synchronized (memoryEmbeddings) {
                    memoryEmbeddings.putIfAbsent(key, embedding);
                }
            }
        }
        return result;
    }

    private double blendedScore(Memory memory, float[] queryEmbedding, float[] memoryEmbedding) {
        double semantic = (cosine(queryEmbedding, memoryEmbedding) + 1.0) / 2.0;
        return semantic * semanticWeight + memory.confidence() * (1.0 - semanticWeight);
    }

    private String key(Memory memory) {
        return memory.id().value() + ":" + Integer.toHexString(memory.content().hashCode());
    }

    private String embeddingText(Memory memory) {
        return memory.category().name() + ": " + memory.content();
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
