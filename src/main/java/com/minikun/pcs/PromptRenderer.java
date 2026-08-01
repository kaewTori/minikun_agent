package com.minikun.pcs;

import com.minikun.character.model.CharacterSpecification;
import com.minikun.pcs.model.CapabilityInstruction;

import java.util.ArrayList;
import java.util.List;

final class PromptRenderer {
    private PromptRenderer() {
    }

    static String render(PromptRequest request) {
        List<String> sections = new ArrayList<>();
        sections.add(character(request.character()));
        sections.add(section("Runtime", request.runtime().content()));
        addOptional(sections, "Conversation", request.conversation() == null ? null : request.conversation().content());
        addOptional(sections, "Memory", request.memory() == null ? null : request.memory().content());
        addOptional(sections, "Knowledge", request.knowledge() == null ? null : request.knowledge().content());
        if (!request.capabilities().isEmpty()) {
            StringBuilder capabilities = new StringBuilder("[Capabilities]");
            for (CapabilityInstruction capability : request.capabilities()) {
                if (capability == null || capability.content() == null || capability.content().isBlank()) {
                    continue;
                }
                capabilities.append("\n").append(capability.name()).append(": ").append(capability.content());
            }
            if (capabilities.length() > "[Capabilities]".length()) {
                sections.add(capabilities.toString());
            }
        }
        sections.add(section("User Message", request.userMessage().content()));
        return String.join("\n\n", sections);
    }

    private static String character(CharacterSpecification specification) {
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

    private static void addOptional(List<String> sections, String label, String content) {
        if (content != null && !content.isBlank()) {
            sections.add(section(label, content));
        }
    }

    private static String section(String label, String content) {
        return "[" + label + "]\n" + content;
    }
}