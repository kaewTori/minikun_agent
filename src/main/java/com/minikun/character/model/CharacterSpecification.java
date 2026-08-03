package com.minikun.character.model;

import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

public record CharacterSpecification(
        String name,
        String version,
        String description,
        String primaryLanguage,
        String fallbackLanguage,
        String role,
        String relationship,
        String defaultMode,
        CharacterMetadata metadata,
        Map<String, LoadingPolicy> loadingPolicies,
        Map<String, com.minikun.pcs.McsSelectionMetadata> selectionMetadata,
        Identity identity,
        Personality personality,
        Values values,
        Communication communication,
        Behavior behavior,
        Reasoning reasoning,
        Interests interests,
        Boundaries boundaries,
        Catchphrases catchphrases) {
    public CharacterSpecification {
        name = requireText(name, "name");
        version = requireText(version, "version");
        description = Objects.requireNonNullElse(description, "");
        primaryLanguage = Objects.requireNonNullElse(primaryLanguage, "");
        fallbackLanguage = Objects.requireNonNullElse(fallbackLanguage, "");
        role = Objects.requireNonNullElse(role, "");
        relationship = Objects.requireNonNullElse(relationship, "");
        defaultMode = Objects.requireNonNullElse(defaultMode, "");
        metadata = Objects.requireNonNull(metadata, "metadata");
        loadingPolicies = Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(loadingPolicies, "loadingPolicies")));
        selectionMetadata = Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(selectionMetadata, "selectionMetadata")));
        identity = Objects.requireNonNull(identity, "identity");
        personality = Objects.requireNonNull(personality, "personality");
        values = Objects.requireNonNull(values, "values");
        communication = Objects.requireNonNull(communication, "communication");
        behavior = Objects.requireNonNull(behavior, "behavior");
        reasoning = Objects.requireNonNull(reasoning, "reasoning");
        interests = Objects.requireNonNull(interests, "interests");
        boundaries = Objects.requireNonNull(boundaries, "boundaries");
        catchphrases = Objects.requireNonNull(catchphrases, "catchphrases");
    }

    public CharacterSpecification(
            String name,
            String version,
            String description,
            String primaryLanguage,
            String fallbackLanguage,
            String role,
            String relationship,
            String defaultMode,
            CharacterMetadata metadata,
            Map<String, LoadingPolicy> loadingPolicies,
            Identity identity,
            Personality personality,
            Values values,
            Communication communication,
            Behavior behavior,
            Reasoning reasoning,
            Interests interests,
            Boundaries boundaries,
            Catchphrases catchphrases) {
        this(name, version, description, primaryLanguage, fallbackLanguage, role, relationship, defaultMode,
                metadata, loadingPolicies, Map.of(), identity, personality, values, communication, behavior,
                reasoning, interests, boundaries, catchphrases);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
