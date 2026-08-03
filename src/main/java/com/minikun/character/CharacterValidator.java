package com.minikun.character;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashSet;

final class CharacterValidator {
    static final List<String> SECTION_NAMES = List.of(
            "identity", "personality", "values", "communication", "behavior",
            "reasoning", "interests", "boundaries", "catchphrases");
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
            validateLoadingPolicies(manifest, errors);
        }

        for (String section : sectionNames) {
            if (files.value(section).isBlank()) {
                errors.add(new CharacterException.Error("EMPTY_SECTION", section, "Section must not be empty"));
            }
        }
        return List.copyOf(errors);
    }

    private void validateLoadingPolicies(JsonNode manifest, List<CharacterException.Error> errors) {
        JsonNode modules = manifest.get("modules");
        if (modules == null) {
            return;
        }
        if (!modules.isObject()) {
            errors.add(new CharacterException.Error("INVALID_MODULES", "manifest.yaml", "modules must be an object"));
            return;
        }

        Set<String> known = new HashSet<>(SECTION_NAMES);
        modules.fieldNames().forEachRemaining(name -> {
            if (!known.contains(name)) {
                errors.add(new CharacterException.Error("UNKNOWN_MODULE", "manifest.yaml", "Unknown module: " + name));
            }
        });
        for (String section : SECTION_NAMES) {
            JsonNode module = modules.get(section);
            if (module == null) {
                errors.add(new CharacterException.Error("MISSING_MODULE", "manifest.yaml", "Missing module: " + section));
                continue;
            }
            JsonNode policy = module.get("loadingPolicy");
            if (policy == null || !policy.isTextual() || policy.asText().isBlank()) {
                errors.add(new CharacterException.Error("MISSING_LOADING_POLICY", "manifest.yaml", "Missing loadingPolicy for module: " + section));
            } else {
                try {
                    com.minikun.character.model.LoadingPolicy.valueOf(policy.asText());
                } catch (IllegalArgumentException exception) {
                    errors.add(new CharacterException.Error("INVALID_LOADING_POLICY", "manifest.yaml", "Invalid loadingPolicy for module: " + section));
                }
            }
        }
    }

    record MapView(java.util.Map<String, String> values) {
        boolean contains(String name) { return values.containsKey(name); }
        String value(String name) { return values.getOrDefault(name, ""); }
    }
}
