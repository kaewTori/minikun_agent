package com.minikun.character.model;

import java.util.List;

public record Personality(List<String> statements) {
    public Personality {
        statements = List.copyOf(statements);
    }
}
