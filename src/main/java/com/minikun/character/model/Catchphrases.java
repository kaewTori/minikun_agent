package com.minikun.character.model;

import java.util.List;

public record Catchphrases(List<String> statements) {
    public Catchphrases {
        statements = List.copyOf(statements);
    }
}
