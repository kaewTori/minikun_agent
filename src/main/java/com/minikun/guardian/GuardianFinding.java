package com.minikun.guardian;

import java.util.Objects;

/** Sanitized deterministic evidence for one homelab condition. */
public record GuardianFinding(
        String code,
        GuardianSeverity severity,
        String component,
        String summary,
        String evidence,
        String recommendedAction,
        String cause,
        String causeConfidence) {

    public GuardianFinding(String code, GuardianSeverity severity, String component, String summary,
            String evidence, String recommendedAction) {
        this(code, severity, component, summary, evidence, recommendedAction,
                "หลักฐานยังไม่พอระบุต้นเหตุ", "UNKNOWN");
    }

    public GuardianFinding {
        code = required(code, "finding code");
        severity = Objects.requireNonNull(severity, "finding severity must not be null");
        component = required(component, "finding component");
        summary = required(summary, "finding summary");
        evidence = normalize(evidence);
        recommendedAction = normalize(recommendedAction);
        cause = required(cause, "finding cause");
        causeConfidence = required(causeConfidence, "cause confidence");
        if (!causeConfidence.equals("CONFIRMED") && !causeConfidence.equals("UNKNOWN")) {
            throw new IllegalArgumentException("cause confidence must be CONFIRMED or UNKNOWN");
        }
    }

    private static String required(String value, String name) {
        String normalized = normalize(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
