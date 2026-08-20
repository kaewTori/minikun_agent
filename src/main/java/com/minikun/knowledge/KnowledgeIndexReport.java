package com.minikun.knowledge;

import java.util.List;

public record KnowledgeIndexReport(
        String root,
        String path,
        int discovered,
        int indexed,
        int unchanged,
        int failed,
        int chunks,
        List<String> errors) {
    public KnowledgeIndexReport {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
