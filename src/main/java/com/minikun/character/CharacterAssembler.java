package com.minikun.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.minikun.character.model.Behavior;
import com.minikun.character.model.Boundaries;
import com.minikun.character.model.Catchphrases;
import com.minikun.character.model.CharacterMetadata;
import com.minikun.character.model.CharacterSpecification;
import com.minikun.character.model.Communication;
import com.minikun.character.model.Identity;
import com.minikun.character.model.Interests;
import com.minikun.character.model.Personality;
import com.minikun.character.model.Reasoning;
import com.minikun.character.model.Values;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

final class CharacterAssembler {
    private final MarkdownSectionParser sections;

    CharacterAssembler(MarkdownSectionParser sections) {
        this.sections = sections;
    }

    CharacterSpecification assemble(Map<String, String> files, JsonNode manifest, JsonNode metadata) {
        JsonNode source = metadata == null || metadata.isNull() ? manifest : metadata;
        CharacterMetadata characterMetadata = new CharacterMetadata(
                ManifestParser.text(source, "/author"),
                parseDate(ManifestParser.text(source, "/created_at")),
                ManifestParser.text(source, "/compatibility/pcs"));

        return new CharacterSpecification(
                ManifestParser.text(manifest, "/name"),
                ManifestParser.text(manifest, "/version"),
                ManifestParser.text(manifest, "/description"),
                ManifestParser.text(manifest, "/language/primary"),
                ManifestParser.text(manifest, "/language/fallback"),
                ManifestParser.text(manifest, "/persona/role"),
                ManifestParser.text(manifest, "/persona/relationship"),
                ManifestParser.text(manifest, "/runtime/default_mode"),
                characterMetadata,
                new Identity(read(files, "identity.md")),
                new Personality(read(files, "personality.md")),
                new Values(read(files, "values.md")),
                new Communication(read(files, "communication.md")),
                new Behavior(read(files, "behavior.md")),
                new Reasoning(read(files, "reasoning.md")),
                new Interests(read(files, "interests.md")),
                new Boundaries(read(files, "boundaries.md")),
                new Catchphrases(read(files, "catchphrases.md")));
    }

    private List<String> read(Map<String, String> files, String name) {
        return sections.parse(files.getOrDefault(name, ""));
    }

    private LocalDate parseDate(String value) {
        if (value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
