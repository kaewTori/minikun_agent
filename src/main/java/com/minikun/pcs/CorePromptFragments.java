package com.minikun.pcs;

import com.minikun.character.model.CharacterSpecification;

import java.util.List;

public final class CorePromptFragments {
    private CorePromptFragments() {
    }

    public static String persona(CharacterSpecification specification) {
        StringBuilder character = new StringBuilder("[Character]");
        append(character, "name", specification.name());
        append(character, "version", specification.version());
        append(character, "description", specification.description());
        append(character, "primaryLanguage", specification.primaryLanguage());
        append(character, "fallbackLanguage", specification.fallbackLanguage());
        append(character, "role", specification.role());
        append(character, "relationship", specification.relationship());
        append(character, "defaultMode", specification.defaultMode());
        appendStatements(character, "identity", specification.identity().statements());
        appendStatements(character, "personality", specification.personality().statements());
        appendStatements(character, "values", specification.values().statements());
        appendStatements(character, "communication", specification.communication().statements());
        appendStatements(character, "behavior", specification.behavior().statements());
        appendStatements(character, "reasoning", specification.reasoning().statements());
        appendStatements(character, "interests", specification.interests().statements());
        appendStatements(character, "boundaries", specification.boundaries().statements());
        appendStatements(character, "catchphrases", specification.catchphrases().statements());
        return character.toString();
    }

    private static void appendStatements(StringBuilder section, String label, List<String> statements) {
        if (!statements.isEmpty()) {
            append(section, label, String.join("; ", statements));
        }
    }

    private static void append(StringBuilder section, String label, String value) {
        if (value != null && !value.isBlank()) {
            section.append("\n").append(label).append(": ").append(value);
        }
    }
}