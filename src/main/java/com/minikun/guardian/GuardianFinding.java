package com.minikun.guardian;

import java.util.Objects;

/** Sanitized deterministic evidence for one homelab condition. */
public record GuardianFinding(
        String code,
        GuardianSeverity severity,
        String component,
        String summary,
        String evidence,
        String recommendedAction) {

    public GuardianFinding {
        code = required(code, "finding code");
        severity = Objects.requireNonNull(severity, "finding severity must not be null");
        component = required(component, "finding component");
        summary = required(summary, "finding summary");
        evidence = normalize(evidence);
        recommendedAction = normalize(recommendedAction);
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
