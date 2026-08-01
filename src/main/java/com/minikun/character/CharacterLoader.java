package com.minikun.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.minikun.character.model.CharacterSpecification;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class CharacterLoader {
    private static final List<String> SECTION_FILES = List.of(
            "identity.md", "personality.md", "values.md", "communication.md", "behavior.md",
            "reasoning.md", "interests.md", "boundaries.md", "catchphrases.md");

    private final CharacterRepository repository;
    private final ManifestParser manifestParser;
    private final MarkdownSectionParser sectionParser;
    private final CharacterValidator validator;
    private final CharacterAssembler assembler;

    public CharacterLoader(Path characterDirectory) {
        this.repository = new CharacterRepository(characterDirectory);
        this.manifestParser = new ManifestParser();
        this.sectionParser = new MarkdownSectionParser();
        this.validator = new CharacterValidator();
        this.assembler = new CharacterAssembler(sectionParser);
    }

    public CharacterSpecification load() {
        return readSpecification();
    }

    public CharacterSpecification reload() {
        return readSpecification();
    }

    private CharacterSpecification readSpecification() {
        Map<String, String> files;
        try {
            files = repository.read();
        } catch (IOException exception) {
            throw failure("Unable to read character files", exception,
                    new CharacterException.Error("IO_ERROR", "", exception.getMessage()));
        }

        JsonNode manifest;
        try {
            manifest = files.containsKey("manifest.yaml")
                    ? manifestParser.parse(files.get("manifest.yaml"))
                    : null;
        } catch (IOException exception) {
            throw failure("Unable to parse manifest", exception,
                    new CharacterException.Error("INVALID_MANIFEST", "manifest.yaml", exception.getMessage()));
        }

        List<CharacterException.Error> errors = validator.validate(
                new CharacterValidator.MapView(files), manifest, SECTION_FILES);
        if (!errors.isEmpty()) {
            throw new CharacterException("Character validation failed", errors);
        }

        JsonNode metadata = null;
        if (files.containsKey("metadata.yaml")) {
            try {
                metadata = manifestParser.parse(files.get("metadata.yaml"));
            } catch (IOException exception) {
                throw failure("Unable to parse metadata", exception,
                        new CharacterException.Error("INVALID_METADATA", "metadata.yaml", exception.getMessage()));
            }
        }
        return assembler.assemble(files, manifest, metadata);
    }

    private CharacterException failure(String message, Throwable cause, CharacterException.Error error) {
        return new CharacterException(message, cause, List.of(error));
    }
}
