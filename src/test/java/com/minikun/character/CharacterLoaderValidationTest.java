package com.minikun.character;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterLoaderValidationTest {
    @Test
    void reportsMissingAndInvalidFilesTogether() throws Exception {
        Path directory = Files.createTempDirectory("mcs-invalid-");
        Files.writeString(directory.resolve("manifest.yaml"), "name: Test\nspecification: Wrong\n");

        CharacterException exception = org.junit.jupiter.api.Assertions.assertThrows(
                CharacterException.class, () -> new CharacterLoader(directory).load());

        assertTrue(exception.errors().stream().anyMatch(error -> error.code().equals("MISSING_FILE")));
        assertTrue(exception.errors().stream().anyMatch(error -> error.code().equals("INVALID_SPECIFICATION")));
        assertEquals(9, exception.errors().stream().filter(error -> error.code().equals("MISSING_FILE")).count());
    }

    @Test
    void rejectsUnknownModule() throws Exception {
        assertHasError("modules:\n  unknown:\n    loadingPolicy: ALWAYS\n", "UNKNOWN_MODULE");
    }

    @Test
    void rejectsMissingModule() throws Exception {
        assertHasError("modules:\n  identity:\n    loadingPolicy: ALWAYS\n", "MISSING_MODULE");
    }

    @Test
    void rejectsMissingLoadingPolicy() throws Exception {
        assertHasError("modules:\n  identity: {}\n", "MISSING_LOADING_POLICY");
    }

    @Test
    void rejectsInvalidLoadingPolicy() throws Exception {
        assertHasError("modules:\n  identity:\n    loadingPolicy: SOMETIMES\n", "INVALID_LOADING_POLICY");
    }

    @Test
    void rejectsDuplicateModuleDefinitions() throws Exception {
        Path directory = validDirectory();
        try {
            Files.writeString(directory.resolve("manifest.yaml"), "name: Test\nversion: 1\nspecification: MCS\nmodules:\n  identity:\n    loadingPolicy: ALWAYS\n  identity:\n    loadingPolicy: DYNAMIC\n");
            CharacterException exception = org.junit.jupiter.api.Assertions.assertThrows(
                    CharacterException.class, () -> new CharacterLoader(directory).load());
            assertTrue(exception.errors().stream().anyMatch(error -> error.code().equals("INVALID_MANIFEST")));
        } finally {
            delete(directory);
        }
    }

    private void assertHasError(String modules, String code) throws Exception {
        Path directory = validDirectory();
        try {
            Files.writeString(directory.resolve("manifest.yaml"), "name: Test\nversion: 1\nspecification: MCS\n" + modules);
            CharacterException exception = org.junit.jupiter.api.Assertions.assertThrows(
                    CharacterException.class, () -> new CharacterLoader(directory).load());
            assertTrue(exception.errors().stream().anyMatch(error -> error.code().equals(code)));
        } finally {
            delete(directory);
        }
    }

    private Path validDirectory() throws Exception {
        Path directory = Files.createTempDirectory("mcs-policy-");
        Files.writeString(directory.resolve("manifest.yaml"), "name: Test\nversion: 1\nspecification: MCS\n");
        List.of("identity", "personality", "values", "communication", "behavior", "reasoning", "interests", "boundaries", "catchphrases")
                .forEach(section -> {
                    try { Files.writeString(directory.resolve(section + ".md"), "content\n"); }
                    catch (Exception exception) { throw new RuntimeException(exception); }
                });
        return directory;
    }

    private void delete(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        }
    }
}
