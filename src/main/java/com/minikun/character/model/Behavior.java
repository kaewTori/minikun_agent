package com.minikun.character.model;

import java.util.List;

public record Behavior(List<String> statements) {
    public Behavior {
        statements = List.copyOf(statements);
    }
}
