package com.minikun.guardian;

import java.time.Instant;
import java.util.Objects;

/** Metadata-only backup verification; backup contents are never exposed to the model. */
public record GuardianBackupSnapshot(
        String name,
        String status,
        Instant lastModifiedAt,
        Long sizeBytes,
        String detail) {

    public GuardianBackupSnapshot {
        name = Objects.requireNonNullElse(name, "").trim();
        status = Objects.requireNonNullElse(status, "UNKNOWN").trim();
        detail = Objects.requireNonNullElse(detail, "").trim();
        if (sizeBytes != null && sizeBytes < 0) throw new IllegalArgumentException("backup size must not be negative");
    }
}
