package com.minikun.character.model;

import java.util.List;

public record Reasoning(List<String> statements) {
    public Reasoning {
        statements = List.copyOf(statements);
    }
}
