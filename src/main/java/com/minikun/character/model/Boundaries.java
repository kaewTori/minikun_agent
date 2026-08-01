package com.minikun.character.model;

import java.util.List;

public record Boundaries(List<String> statements) {
    public Boundaries {
        statements = List.copyOf(statements);
    }
}
