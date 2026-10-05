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
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process running = process;
            java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
            Thread drain = Thread.ofVirtual().start(() -> {
                try (var stream = running.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = stream.read(buffer)) >= 0) {
                        synchronized (captured) {
                            int remaining = MAX_OUTPUT_BYTES - captured.size();
                            if (remaining > 0) captured.write(buffer, 0, Math.min(count, remaining));
                        }
                    }
                } catch (IOException ignored) { }
            });
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
                process.getInputStream().close();
                return new GuardianCommandResult(-1, true, "action timed out");
            }
            drain.join(1000);
            process.getInputStream().close();
            synchronized (captured) { return new GuardianCommandResult(process.exitValue(), false, captured.toString(StandardCharsets.UTF_8)); }
        } catch (IOException exception) {
            return new GuardianCommandResult(-1, false, "configured action could not be started");
        } catch (InterruptedException exception) {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try { process.getInputStream().close(); } catch (IOException ignored) { }
            }
            Thread.currentThread().interrupt();
            return new GuardianCommandResult(-1, true, "action was interrupted");
        }
    }
}
