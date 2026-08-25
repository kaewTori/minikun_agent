package com.minikun.sync;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryPairedDeviceRepository implements PairedDeviceRepository {
    private final ConcurrentHashMap<UUID, PairedDevice> values = new ConcurrentHashMap<>();

    @Override public long activeCount(Instant now) {
        return values.values().stream().filter(value -> active(value, now)).count();
    }

    @Override public Optional<PairedDevice> findActiveByTokenHash(String tokenHash, Instant now) {
        return values.values().stream().filter(value -> value.tokenHash().equals(tokenHash) && active(value, now))
                .findFirst();
    }

    @Override public void save(PairedDevice device) { values.put(device.id(), device); }

    @Override public void touch(UUID deviceId, Instant seenAt) {
        values.computeIfPresent(deviceId, (id, value) -> new PairedDevice(value.id(), value.ownerId(),
                value.name(), value.tokenHash(), value.createdAt(), seenAt, value.expiresAt(), value.revokedAt()));
    }

    @Override public List<PairedDevice> list(String ownerId) {
        return values.values().stream().filter(value -> value.ownerId().equals(ownerId) && value.revokedAt() == null)
                .sorted((left, right) -> right.lastSeenAt().compareTo(left.lastSeenAt())).toList();
    }

    @Override public boolean revoke(String ownerId, UUID deviceId, Instant revokedAt) {
        java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
        values.computeIfPresent(deviceId, (id, value) -> {
            if (!value.ownerId().equals(ownerId) || value.revokedAt() != null) return value;
            changed.set(true);
            return new PairedDevice(value.id(), value.ownerId(), value.name(), value.tokenHash(), value.createdAt(),
                    value.lastSeenAt(), value.expiresAt(), revokedAt);
        });
        return changed.get();
    }

    private boolean active(PairedDevice value, Instant now) {
        return value.revokedAt() == null && value.expiresAt().isAfter(now);
    }
}
