package com.minikun.guardian;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Checks existence, age and size metadata for an application-owned backup allowlist. */
public final class GuardianBackupChecker {
    private final List<GuardianBackupTarget> targets;
    private final Duration maximumAge;
    private final Clock clock;

    public GuardianBackupChecker(List<GuardianBackupTarget> targets, Duration maximumAge, Clock clock) {
        this.targets = List.copyOf(targets == null ? List.of() : targets);
        if (maximumAge == null || maximumAge.isZero() || maximumAge.isNegative()) {
            throw new IllegalArgumentException("guardian backup maximum age must be positive");
        }
        this.maximumAge = maximumAge;
        this.clock = Objects.requireNonNull(clock, "guardian backup clock must not be null");
    }

    public List<GuardianBackupSnapshot> inspect() {
        return targets.stream().map(this::inspect).toList();
    }

    private GuardianBackupSnapshot inspect(GuardianBackupTarget target) {
        if (!Files.exists(target.path())) {
            return new GuardianBackupSnapshot(target.name(), "MISSING", null, null, "configured backup is missing");
        }
        try {
            Instant modified = Files.getLastModifiedTime(target.path()).toInstant();
            Long size = Files.isRegularFile(target.path()) ? Files.size(target.path()) : null;
            boolean stale = modified.plus(maximumAge).isBefore(clock.instant());
            return new GuardianBackupSnapshot(target.name(), stale ? "STALE" : "UP", modified, size,
                    stale ? "backup is older than the configured maximum age" : "backup metadata is current");
        } catch (IOException | RuntimeException exception) {
            return new GuardianBackupSnapshot(target.name(), "UNAVAILABLE", null, null,
                    "backup metadata could not be inspected");
        }
    }
}
