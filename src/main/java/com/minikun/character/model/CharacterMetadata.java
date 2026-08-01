package com.minikun.character.model;

import java.time.LocalDate;
import java.util.Objects;

public record CharacterMetadata(String author, LocalDate createdAt, String compatibility) {
    public CharacterMetadata {
        author = Objects.requireNonNullElse(author, "");
        compatibility = Objects.requireNonNullElse(compatibility, "");
    }
}
