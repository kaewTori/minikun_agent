package com.minikun.pcs;

import com.minikun.character.model.CharacterSpecification;
import com.minikun.character.model.LoadingPolicy;

import java.util.List;
import java.util.Objects;
import java.util.ArrayList;

public record McsModule(String name, LoadingPolicy loadingPolicy, List<String> statements,
    java.util.Optional<McsSelectionMetadata> selectionMetadata) {
    public McsModule {
        name = requireText(name, "name");
        loadingPolicy = Objects.requireNonNull(loadingPolicy, "loadingPolicy");
        statements = List.copyOf(Objects.requireNonNull(statements, "statements"));
        selectionMetadata = Objects.requireNonNull(selectionMetadata, "selectionMetadata");
    }

    public McsModule(String name, LoadingPolicy loadingPolicy, List<String> statements) {
        this(name, loadingPolicy, statements, java.util.Optional.empty());
    }

    public SectionKind sectionKind() {
        return SectionKind.fromModuleName(name);
    }

    public static List<McsModule> orderedFrom(CharacterSpecification specification) {
        Objects.requireNonNull(specification, "specification");
        List<McsModule> modules = new ArrayList<>();
        for (String name : specification.loadingPolicies().keySet()) {
            modules.add(module(specification, name));
        }
        return List.copyOf(modules);
    }

    private static McsModule module(CharacterSpecification specification, String name) {
        List<String> statements = switch (name) {
            case "identity" -> specification.identity().statements();
            case "personality" -> specification.personality().statements();
            case "values" -> specification.values().statements();
            case "communication" -> specification.communication().statements();
            case "behavior" -> specification.behavior().statements();
            case "reasoning" -> specification.reasoning().statements();
            case "interests" -> specification.interests().statements();
            case "boundaries" -> specification.boundaries().statements();
            case "catchphrases" -> specification.catchphrases().statements();
            default -> throw new IllegalArgumentException("Unknown MCS module: " + name);
        };
        return new McsModule(name,
            specification.loadingPolicies().getOrDefault(name, LoadingPolicy.ALWAYS), statements,
            java.util.Optional.ofNullable(specification.selectionMetadata().get(name)));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
