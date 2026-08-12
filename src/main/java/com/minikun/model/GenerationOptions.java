package com.minikun.model;

import java.util.List;

public record GenerationOptions(
        Double temperature,
        Integer maxTokens,
        List<String> stop) {
    public GenerationOptions {
        stop = stop == null ? List.of() : List.copyOf(stop);
    }

    public boolean isEmpty() {
        return temperature == null && maxTokens == null && stop.isEmpty();
    }
}
