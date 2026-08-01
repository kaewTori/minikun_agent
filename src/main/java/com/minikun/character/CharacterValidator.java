package com.minikun.character;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class CharacterValidator {
    private static final Set<String> REQUIRED_FILES = Set.of(
            "manifest.yaml", "identity.md", "personality.md", "values.md", "communication.md",
            "behavior.md", "reasoning.md", "interests.md", "boundaries.md", "catchphrases.md");

    List<CharacterException.Error> validate(MapView files, JsonNode manifest, List<String> sectionNames) {
        List<CharacterException.Error> errors = new ArrayList<>();
        REQUIRED_FILES.stream()
                .filter(file -> !files.contains(file))
                .forEach(file -> errors.add(new CharacterException.Error("MISSING_FILE", file, "Required file is missing")));

        if (manifest == null || manifest.isNull()) {
            errors.add(new CharacterException.Error("INVALID_MANIFEST", "manifest.yaml", "Manifest must contain a YAML object"));
        } else {
            if (ManifestParser.text(manifest, "/name").isBlank()) {
                errors.add(new CharacterException.Error("MISSING_FIELD", "manifest.yaml", "Missing manifest field: name"));
            }
            if (ManifestParser.text(manifest, "/version").isBlank()) {
                errors.add(new CharacterException.Error("MISSING_FIELD", "manifest.yaml", "Missing manifest field: version"));
            }
            if (!"MCS".equalsIgnoreCase(ManifestParser.text(manifest, "/specification"))) {
                errors.add(new CharacterException.Error("INVALID_SPECIFICATION", "manifest.yaml", "specification must be MCS"));
            }
        }

        for (String section : sectionNames) {
            if (files.value(section).isBlank()) {
                errors.add(new CharacterException.Error("EMPTY_SECTION", section, "Section must not be empty"));
            }
        }
        return List.copyOf(errors);
    }

    record MapView(java.util.Map<String, String> values) {
        boolean contains(String name) { return values.containsKey(name); }
        String value(String name) { return values.getOrDefault(name, ""); }
    }
}
