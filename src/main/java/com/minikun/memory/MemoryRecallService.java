package com.minikun.memory;

import java.util.List;
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

    public KnowledgeContext recall() {
        return pipeline.apply(repository.findAll());
    }
}