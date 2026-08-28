package com.minikun.visual;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record InspirationBoardItem(UUID id, UUID boardId, String imageUrl, String sourceUrl,
        String title, String description, String origin, Instant createdAt) {
    public InspirationBoardItem {
        Objects.requireNonNull(id); Objects.requireNonNull(boardId); Objects.requireNonNull(imageUrl);
        sourceUrl = Objects.requireNonNullElse(sourceUrl, "");
        title = Objects.requireNonNullElse(title, "");
        description = Objects.requireNonNullElse(description, "");
        origin = Objects.requireNonNullElse(origin, "web");
        Objects.requireNonNull(createdAt);
    }
}
