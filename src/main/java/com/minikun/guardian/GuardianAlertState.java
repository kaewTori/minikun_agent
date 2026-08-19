package com.minikun.guardian;

import java.time.Instant;
import java.util.Objects;

record GuardianAlertState(
        String observedFingerprint,
        String notifiedFingerprint,
        String status,
        int consecutiveIssues,
        Instant lastNotifiedAt) {

    static final GuardianAlertState EMPTY = new GuardianAlertState("", "", "UP", 0, null);

    GuardianAlertState {
        observedFingerprint = Objects.requireNonNullElse(observedFingerprint, "");
        notifiedFingerprint = Objects.requireNonNullElse(notifiedFingerprint, "");
        status = Objects.requireNonNullElse(status, "UP");
        if (consecutiveIssues < 0) throw new IllegalArgumentException("consecutive issues must not be negative");
    }
}
