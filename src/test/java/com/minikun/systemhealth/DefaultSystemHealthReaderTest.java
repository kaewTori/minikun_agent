package com.minikun.systemhealth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class DefaultSystemHealthReaderTest {
    @Test
    void readsHostSnapshotWithoutExposingDependencyHost() {
        SystemHealthReport report = new DefaultSystemHealthReader(
                Path.of("/tmp"),
                List.of(new SystemHealthDependency("unused", "127.0.0.1", 1)),
                Duration.ofMillis(50),
                85,
                90).read();

        assertTrue(report.status().equals("UP") || report.status().equals("WARNING"));
        assertTrue(report.cpu().containsKey("logical_processors"));
        assertTrue(report.memory().containsKey("status"));
        assertTrue(report.disk().containsKey("status"));
        assertTrue(report.jvm().containsKey("java_version"));
        assertEquals("DOWN", report.dependencies().get("unused").get("status"));
        assertEquals("CONNECTION_REFUSED", report.dependencies().get("unused").get("failure_reason"));
        assertEquals(85.0, report.memory().get("warning_threshold_percent"));
        assertEquals(90.0, report.cpu().get("warning_threshold_percent"));
        assertTrue(!report.dependencies().get("unused").containsKey("host"));
    }
}
