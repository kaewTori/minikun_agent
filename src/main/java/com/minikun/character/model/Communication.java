package com.minikun.character.model;

import java.util.List;

public record Communication(List<String> statements) {
    public Communication {
        statements = List.copyOf(statements);
    }
}
