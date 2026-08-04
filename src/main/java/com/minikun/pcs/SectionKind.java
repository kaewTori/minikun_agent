package com.minikun.pcs;

import java.util.Locale;

public enum SectionKind {
    IDENTITY("identity"),
    PERSONALITY("personality"),
    VALUES("values"),
    COMMUNICATION("communication"),
    BEHAVIOR("behavior"),
    REASONING("reasoning"),
    INTERESTS("interests"),
    BOUNDARIES("boundaries"),
    CATCHPHRASES("catchphrases");

    private final String moduleName;

    SectionKind(String moduleName) {
        this.moduleName = moduleName;
    }

    public String moduleName() {
        return moduleName;
    }

    public static SectionKind fromModuleName(String moduleName) {
        if (moduleName == null || moduleName.isBlank()) {
            throw new IllegalArgumentException("module name must not be blank");
        }
        String normalized = moduleName.trim().toLowerCase(Locale.ROOT);
        for (SectionKind kind : values()) {
            if (kind.moduleName.equals(normalized)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown section kind: " + moduleName);
    }
}