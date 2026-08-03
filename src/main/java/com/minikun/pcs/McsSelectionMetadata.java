package com.minikun.pcs;

import java.util.List;
import java.util.Objects;

public record McsSelectionMetadata(List<String> literalTerms) {
    public McsSelectionMetadata {
        literalTerms = List.copyOf(Objects.requireNonNull(literalTerms, "literalTerms"));
    }

    public static McsSelectionMetadata empty() {
        return new McsSelectionMetadata(List.of());
    }
}
