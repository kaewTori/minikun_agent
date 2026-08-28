package com.minikun.visual;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record InspirationBoard(UUID id, String ownerId, String title, Instant createdAt, Instant updatedAt) {
    public InspirationBoard {
        Objects.requireNonNull(id); Objects.requireNonNull(ownerId); Objects.requireNonNull(title);
        Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
    }
}
