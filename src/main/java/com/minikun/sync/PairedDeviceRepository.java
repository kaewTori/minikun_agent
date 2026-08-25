package com.minikun.sync;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PairedDeviceRepository {
    long activeCount(Instant now);

    Optional<PairedDevice> findActiveByTokenHash(String tokenHash, Instant now);

    void save(PairedDevice device);

    void touch(UUID deviceId, Instant seenAt);

    List<PairedDevice> list(String ownerId);

    boolean revoke(String ownerId, UUID deviceId, Instant revokedAt);
}
