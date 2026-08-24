package com.minikun.systemhealth;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemHealthControllerTest {
    @Test
    void returnsSanitizedReaderSnapshot() {
        SystemHealthReport expected = new SystemHealthReport(
                "UP", true, Map.of("status", "UP"), Map.of("status", "UP"),
                Map.of("status", "UP"), Map.of("status", "UP"), Map.of("status", "UP"),
                Map.of("status", "RUNNING"), Map.of());
        SystemHealthController controller = new SystemHealthController(() -> expected);

        assertSame(expected, controller.report(null));
    }
}
