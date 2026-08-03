package com.minikun.pcs;

import com.minikun.character.model.CharacterSpecification;

import java.util.List;

public final class CorePromptFragments {
    private CorePromptFragments() {
    }

    public static String persona(CharacterSpecification specification) {
        return persona(specification, McsModule.orderedFrom(specification));
    }

    public static String persona(CharacterSpecification specification, List<McsModule> modules) {
        java.util.Objects.requireNonNull(specification, "specification");
        modules = List.copyOf(java.util.Objects.requireNonNull(modules, "modules"));
        StringBuilder character = new StringBuilder("[Character]");
        append(character, "name", specification.name());
        append(character, "version", specification.version());
        append(character, "description", specification.description());
        append(character, "primaryLanguage", specification.primaryLanguage());
        append(character, "fallbackLanguage", specification.fallbackLanguage());
        append(character, "role", specification.role());
        append(character, "relationship", specification.relationship());
        append(character, "defaultMode", specification.defaultMode());
        for (McsModule module : modules) {
            appendStatements(character, module.name(), module.statements());
        }
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