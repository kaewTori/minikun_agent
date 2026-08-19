package com.minikun.guardian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Executes a preconfigured argv list directly; no shell and no model-provided command fragments. */
public final class ProcessGuardianCommandRunner implements GuardianCommandRunner {
    private static final int MAX_OUTPUT_BYTES = 16_384;

    @Override
    public GuardianCommandResult run(List<String> command, Duration timeout) {
        Objects.requireNonNull(command, "guardian command must not be null");
        if (command.isEmpty()) throw new IllegalArgumentException("guardian command must not be empty");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
                return new GuardianCommandResult(-1, true, "action timed out");
            }
            byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES);
            return new GuardianCommandResult(process.exitValue(), false,
                    new String(output, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return new GuardianCommandResult(-1, false, "configured action could not be started");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new GuardianCommandResult(-1, true, "action was interrupted");
        }
    }
}
