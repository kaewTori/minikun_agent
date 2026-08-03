package com.minikun.pcs;

import com.minikun.character.model.CharacterSpecification;
import com.minikun.character.model.LoadingPolicy;

import java.util.List;
import java.util.Objects;

public record McsModule(String name, LoadingPolicy loadingPolicy, List<String> statements) {
    public McsModule {
        name = requireText(name, "name");
        loadingPolicy = Objects.requireNonNull(loadingPolicy, "loadingPolicy");
        statements = List.copyOf(Objects.requireNonNull(statements, "statements"));
    }

    public static List<McsModule> orderedFrom(CharacterSpecification specification) {
        Objects.requireNonNull(specification, "specification");
        return List.of(
                module(specification, "identity", specification.identity().statements()),
                module(specification, "personality", specification.personality().statements()),
                module(specification, "values", specification.values().statements()),
                module(specification, "communication", specification.communication().statements()),
                module(specification, "behavior", specification.behavior().statements()),
                module(specification, "reasoning", specification.reasoning().statements()),
                module(specification, "interests", specification.interests().statements()),
                module(specification, "boundaries", specification.boundaries().statements()),
                module(specification, "catchphrases", specification.catchphrases().statements()));
    }

    private static McsModule module(CharacterSpecification specification, String name, List<String> statements) {
        return new McsModule(name,
                specification.loadingPolicies().getOrDefault(name, LoadingPolicy.ALWAYS), statements);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
