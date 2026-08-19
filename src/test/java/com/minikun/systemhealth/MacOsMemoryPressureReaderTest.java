package com.minikun.systemhealth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MacOsMemoryPressureReaderTest {
    @Test
    void parsesEffectiveAvailableMemoryAndRejectsInvalidOutput() {
        assertEquals(27.0, MacOsMemoryPressureReader.parseAvailablePercent("""
                The system has 25769803776 bytes.
                System-wide memory free percentage: 27%
                """).orElseThrow());
        assertTrue(MacOsMemoryPressureReader.parseAvailablePercent(
                "System-wide memory free percentage: 101%").isEmpty());
        assertTrue(MacOsMemoryPressureReader.parseAvailablePercent("unavailable").isEmpty());
    }
}
