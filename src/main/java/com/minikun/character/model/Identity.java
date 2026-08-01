package com.minikun.character.model;

import java.util.List;

public record Identity(List<String> statements) {
    public Identity {
        statements = List.copyOf(statements);
    }
}
