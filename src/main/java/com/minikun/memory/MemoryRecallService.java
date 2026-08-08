package com.minikun.memory;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import com.minikun.memory.model.Memory;
import com.minikun.pcs.model.KnowledgeContext;

public class MemoryRecallService {
    private final MemoryRepository repository;
    private final Function<List<Memory>, KnowledgeContext> pipeline;

    public MemoryRecallService(MemoryRepository repository,
            Function<List<Memory>, KnowledgeContext> pipeline) {
        this.repository = repository;
        this.pipeline = pipeline;
    }

    public KnowledgeContext recall(MemoryScope scope, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("memory retrieval limit must not be negative");
        }
        Objects.requireNonNull(scope, "scope must not be null");
        if (limit == 0) {
            return new KnowledgeContext("");
        }
        return pipeline.apply(repository.find(scope, limit));
    }
}