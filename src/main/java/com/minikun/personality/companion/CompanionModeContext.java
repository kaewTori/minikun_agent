package com.minikun.personality.companion;

import java.util.Objects;

public record CompanionModeContext(CompanionMode mode, boolean explicitlyChanged, String instruction) {
    public CompanionModeContext {
        Objects.requireNonNull(mode, "companion mode must not be null");
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("companion mode instruction must not be blank");
        }
        instruction = instruction.trim();
    }
}
