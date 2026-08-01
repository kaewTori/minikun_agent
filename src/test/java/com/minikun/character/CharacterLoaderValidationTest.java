package com.minikun.character;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
