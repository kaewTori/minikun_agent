package com.minikun.systemhealth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the macOS effective available-memory percentage without invoking a shell. */
final class MacOsMemoryPressureReader {
    private static final Pattern FREE_PERCENT = Pattern.compile(
            "System-wide memory free percentage:\\s*(\\d+(?:\\.\\d+)?)%");
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final int MAX_OUTPUT_BYTES = 4096;

    OptionalDouble readAvailablePercent() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
            return OptionalDouble.empty();
        }
        Process process = null;
        try {
            process = new ProcessBuilder("/usr/bin/memory_pressure", "-Q")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return OptionalDouble.empty();
            }
            if (process.exitValue() != 0) return OptionalDouble.empty();
            String output = new String(
                    process.getInputStream().readNBytes(MAX_OUTPUT_BYTES), StandardCharsets.UTF_8);
            return parseAvailablePercent(output);
        } catch (IOException exception) {
            return OptionalDouble.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return OptionalDouble.empty();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    static OptionalDouble parseAvailablePercent(String output) {
        if (output == null) return OptionalDouble.empty();
        Matcher matcher = FREE_PERCENT.matcher(output);
        if (!matcher.find()) return OptionalDouble.empty();
        try {
            double value = Double.parseDouble(matcher.group(1));
            return value >= 0 && value <= 100 ? OptionalDouble.of(value) : OptionalDouble.empty();
        } catch (NumberFormatException exception) {
            return OptionalDouble.empty();
        }
    }
}
