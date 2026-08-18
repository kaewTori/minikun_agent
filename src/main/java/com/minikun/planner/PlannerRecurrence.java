package com.minikun.planner;

import java.util.Locale;

public enum PlannerRecurrence {
    NONE,
    DAILY,
    WEEKLY;

    public static PlannerRecurrence parse(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? NONE : valueOf(normalized);
    }
}
