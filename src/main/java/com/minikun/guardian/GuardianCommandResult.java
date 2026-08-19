package com.minikun.guardian;

import java.util.Objects;

public record GuardianCommandResult(int exitCode, boolean timedOut, String output) {
    public GuardianCommandResult {
        output = Objects.requireNonNullElse(output, "").trim();
    }

    public boolean successful() {
        return !timedOut && exitCode == 0;
    }
}
