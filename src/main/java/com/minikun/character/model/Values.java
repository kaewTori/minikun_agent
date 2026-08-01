package com.minikun.character.model;

import java.util.List;

public record Values(List<String> statements) {
    public Values {
        statements = List.copyOf(statements);
    }
}
