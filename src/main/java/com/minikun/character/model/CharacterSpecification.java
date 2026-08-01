package com.minikun.character.model;

import java.util.Objects;

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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
