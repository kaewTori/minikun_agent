package com.minikun.personality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.personality.model.Preference;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PreferencePolicyTest {
    @Test
    void lowConfidenceAndExpiredPreferencesAreInactive() {
        Instant now = Instant.now();
        assertTrue(new Preference("default", "language", "th", .8, now)
                .activeAt(now, Duration.ofDays(365), .5));
        assertFalse(new Preference("default", "language", "th", .2, now)
                .activeAt(now, Duration.ofDays(365), .5));
        assertFalse(new Preference("default", "language", "th", .8, now.minus(Duration.ofDays(366)))
                .activeAt(now, Duration.ofDays(365), .5));
    }
}
