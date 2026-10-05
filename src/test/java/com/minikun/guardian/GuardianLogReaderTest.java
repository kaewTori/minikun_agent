package com.minikun.guardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuardianLogReaderTest {
    @TempDir Path directory;

    @Test
    void readsOnlyAllowlistedSourceAndRedactsSecrets() throws Exception {
        Path log = directory.resolve("application.log");
        Files.writeString(log, "INFO started\nWARN token=top-secret retry\nERROR password: hunter2 failed\n");
        GuardianLogReader reader = new GuardianLogReader(List.of(new GuardianLogSource("application", log)));

        GuardianLogSnapshot result = reader.read("application", 20);

        assertEquals("UP", result.status());
        assertEquals(1, result.warningCount());
        assertEquals(1, result.errorCount());
        assertTrue(result.lines().stream().anyMatch(line -> line.contains("[REDACTED]")));
        assertFalse(String.join("\n", result.lines()).contains("top-secret"));
        assertFalse(String.join("\n", result.lines()).contains("hunter2"));
        assertThrows(IllegalArgumentException.class, () -> reader.read("../private", 20));
    }

    @Test
    void reportsUnavailableWithoutExposingConfiguredPath() {
        GuardianLogReader reader = new GuardianLogReader(List.of(
                new GuardianLogSource("missing", directory.resolve("secret/location.log"))));

        GuardianLogSnapshot result = reader.read("missing", 20);

        assertEquals("UNAVAILABLE", result.status());
        assertEquals("FILE_MISSING", result.unavailableReason());
        assertTrue(result.lines().isEmpty());
        assertFalse(result.toString().contains("secret/location"));
    }

    @Test
    void redactsAuthorizationAndJsonCredentialsBeforeEvidenceLeavesReader() throws Exception {
        Path log = directory.resolve("credentials.log");
        Files.writeString(log, "ERROR Authorization: Bearer bearer-secret\n"
                + "ERROR Authorization=Basic basic-secret\n"
                + "ERROR {\"password\": \"has spaces secret\", \"api_key\": \"api-secret\"}\n");
        String lines = String.join("\n", new GuardianLogReader(List.of(
                new GuardianLogSource("application", log))).read("application", 20).lines());
        assertFalse(lines.contains("bearer-secret"));
        assertFalse(lines.contains("basic-secret"));
        assertFalse(lines.contains("has spaces secret"));
        assertFalse(lines.contains("api-secret"));
        assertTrue(lines.contains("[REDACTED]"));
    }
}
