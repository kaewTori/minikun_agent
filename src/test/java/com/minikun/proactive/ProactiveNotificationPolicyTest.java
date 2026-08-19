package com.minikun.proactive;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class ProactiveNotificationPolicyTest {
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    @Test
    void blocksOvernightQuietHoursAndAllowsTheBoundaryAtMorning() {
        ProactiveNotificationPolicy policy = new ProactiveNotificationPolicy(
                true, BANGKOK, LocalTime.of(22, 0), LocalTime.of(7, 0));

        assertFalse(policy.allows(Instant.parse("2026-08-19T15:00:00Z"))); // 22:00 local
        assertFalse(policy.allows(Instant.parse("2026-08-18T23:59:00Z"))); // 06:59 local
        assertTrue(policy.allows(Instant.parse("2026-08-19T00:00:00Z"))); // 07:00 local
        assertTrue(policy.allows(Instant.parse("2026-08-19T04:00:00Z"))); // 11:00 local
    }

    @Test
    void equalBoundariesMeanNoQuietHours() {
        ProactiveNotificationPolicy policy = new ProactiveNotificationPolicy(
                true, BANGKOK, LocalTime.of(7, 0), LocalTime.of(7, 0));

        assertTrue(policy.allows(Instant.parse("2026-08-19T15:00:00Z")));
    }

    @Test
    void disabledPolicyBlocksEveryProactiveNotification() {
        ProactiveNotificationPolicy policy = new ProactiveNotificationPolicy(
                false, BANGKOK, LocalTime.of(22, 0), LocalTime.of(7, 0));

        assertFalse(policy.allows(Instant.parse("2026-08-19T04:00:00Z")));
    }
}
