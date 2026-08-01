package com.minikun.character;

import com.minikun.character.model.CharacterSpecification;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterLoaderTest {
    private static final Path MCS_ROOT = Path.of("../../config/minikun-agent/mcs");

    @Test
    void loadsCurrentMinikunSpecification() {
        CharacterSpecification specification = new CharacterLoader(MCS_ROOT).load();

        assertEquals("Minikun", specification.name());
        assertEquals("2.1.0", specification.version());
        assertEquals("th", specification.primaryLanguage());
        assertTrue(specification.personality().statements().contains("Warm"));
        assertTrue(specification.identity().statements().contains("Personal AI Companion"));
    }

    @Test
    void returnedSectionsAreImmutable() {
        CharacterSpecification specification = new CharacterLoader(MCS_ROOT).load();

        assertThrows(UnsupportedOperationException.class,
                () -> specification.values().statements().add("new value"));
    }
}
