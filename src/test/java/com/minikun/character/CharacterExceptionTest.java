package com.minikun.character;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CharacterExceptionTest {
    @Test
    void errorListIsImmutable() {
        CharacterException exception = new CharacterException("invalid", List.of(
                new CharacterException.Error("TEST", "fixture", "invalid")));

        assertThrows(UnsupportedOperationException.class,
                () -> exception.errors().add(new CharacterException.Error("OTHER", "", "")));
    }
}
