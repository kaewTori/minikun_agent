package com.minikun.memory;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import com.minikun.memory.model.Memory;
import com.minikun.pcs.model.KnowledgeContext;

public class MemoryRecallService {
    private final MemoryRepository repository;
    private final Function<List<Memory>, KnowledgeContext> pipeline;
    private final MemoryRanker ranker;

    public MemoryRecallService(MemoryRepository repository,
            Function<List<Memory>, KnowledgeContext> pipeline) {
        this(repository, pipeline, MemoryRelevanceRanker::rank);
    }

    public MemoryRecallService(MemoryRepository repository,
            Function<List<Memory>, KnowledgeContext> pipeline,
            MemoryRanker ranker) {
        this.repository = repository;
        this.pipeline = pipeline;
        this.ranker = Objects.requireNonNull(ranker, "memory ranker must not be null");
    }

    public static boolean historical(String query) {
        return query != null && java.util.regex.Pattern.compile(
                "เมื่อก่อน|แต่ก่อน|สมัยก่อน|ในอดีต|เคยชอบ|ตอนนั้น|ย้อนดู|ย้อนหลัง|เดือนก่อน|ปีที่แล้ว|ประวัติ|เปลี่ยน.*(อะไร|อย่างไร)|previously|used to|history|last (month|year)|\\b20[0-9]{2}\\b",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(query).find();
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

    public KnowledgeContext recall(LongTermMemoryScope scope, String query, int limit) {
        if (limit < 0) throw new IllegalArgumentException("memory retrieval limit must not be negative");
        Objects.requireNonNull(scope, "scope must not be null");
        if (limit == 0) return new KnowledgeContext("");
        List<Memory> candidates = repository.findLongTerm(scope, query, Math.max(limit * 5, limit));
        List<Memory> ranked = ranker.rank(candidates, query, limit);
        return pipeline.apply(ranked);
    }
}
