package com.minikun.guardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuardianBackupCheckerTest {
    @TempDir Path directory;

    @Test
    void distinguishesCurrentStaleAndMissingBackups() throws Exception {
        Instant now = Instant.parse("2026-08-20T12:00:00Z");
        Path current = Files.writeString(directory.resolve("current.dump"), "ok");
        Path stale = Files.writeString(directory.resolve("stale.dump"), "old");
        Files.setLastModifiedTime(current, FileTime.from(now.minus(Duration.ofHours(1))));
        Files.setLastModifiedTime(stale, FileTime.from(now.minus(Duration.ofDays(3))));
        GuardianBackupChecker checker = new GuardianBackupChecker(List.of(
                new GuardianBackupTarget("current", current),
                new GuardianBackupTarget("stale", stale),
                new GuardianBackupTarget("missing", directory.resolve("missing.dump"))),
                Duration.ofHours(36), Clock.fixed(now, ZoneOffset.UTC));

        List<GuardianBackupSnapshot> results = checker.inspect();

        assertEquals("UP", results.get(0).status());
        assertEquals("STALE", results.get(1).status());
        assertEquals("MISSING", results.get(2).status());
        assertNull(results.get(2).lastModifiedAt());
    }
}
