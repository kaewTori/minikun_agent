package com.minikun.sync;

import java.time.Instant;
import java.util.UUID;

public record PairedDevice(
        UUID id,
        String ownerId,
        String name,
        String tokenHash,
        Instant createdAt,
        Instant lastSeenAt,
        Instant expiresAt,
        Instant revokedAt) {
}
