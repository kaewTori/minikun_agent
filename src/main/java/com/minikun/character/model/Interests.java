package com.minikun.character.model;

import java.util.List;

public record Interests(List<String> statements) {
    public Interests {
        statements = List.copyOf(statements);
    }
}
