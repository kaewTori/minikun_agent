package com.minikun.model;

import java.util.List;

public record GenerationOptions(
        Double temperature,
        Integer maxTokens,
        List<String> stop, Reasoning reasoning) {
    public enum Reasoning {
        OFF, AUTO, LOW, MEDIUM, HIGH;
        @com.fasterxml.jackson.annotation.JsonCreator
        public static Reasoning parse(String value) { return valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
    }
    public GenerationOptions(Double temperature, Integer maxTokens, List<String> stop) {
        this(temperature, maxTokens, stop, Reasoning.OFF);
    }
    public GenerationOptions {
        reasoning = reasoning == null ? Reasoning.OFF : reasoning;
        stop = stop == null ? List.of() : List.copyOf(stop);
    }

    public boolean isEmpty() {
        return temperature == null && maxTokens == null && stop.isEmpty() && reasoning == Reasoning.OFF;
    }
}
