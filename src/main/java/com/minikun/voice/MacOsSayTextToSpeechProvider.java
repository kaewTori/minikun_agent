package com.minikun.voice;

import com.minikun.guardian.GuardianCommandResult;
import com.minikun.guardian.GuardianCommandRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Synthesizes speech through macOS local voices without network requests. */
public final class MacOsSayTextToSpeechProvider implements TextToSpeechProvider {
    private final GuardianCommandRunner runner;
    private final Path sayExecutable;
    private final Duration timeout;
    private final long maxOutputBytes;

    public MacOsSayTextToSpeechProvider(
            GuardianCommandRunner runner, Path sayExecutable, Duration timeout, long maxOutputBytes) {
        this.runner = Objects.requireNonNull(runner, "voice command runner must not be null");
        this.sayExecutable = Objects.requireNonNull(sayExecutable, "say path must not be null").normalize();
        if (!sayExecutable.isAbsolute()) throw new IllegalArgumentException("say path must be absolute");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("speech synthesis timeout must be positive");
        }
        if (maxOutputBytes < 1024) throw new IllegalArgumentException("voice output limit must be at least 1024");
        this.timeout = timeout;
        this.maxOutputBytes = maxOutputBytes;
    }

    @Override
    public VoiceAudio synthesize(String text, String systemVoice, String format, double speed) {
        if (!available()) throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                "macOS speech synthesis is unavailable");
        String normalizedFormat = normalizeFormat(format);
        int wordsPerMinute = (int) Math.round(190 * speed);
        Path input = null;
        Path output = null;
        try {
            input = Files.createTempFile("minikun-speech-input-", ".txt");
            Files.writeString(input, text, StandardCharsets.UTF_8);
            output = Files.createTempFile("minikun-speech-", "." + normalizedFormat);
            String fileFormat = "wav".equals(normalizedFormat) ? "WAVE" : "AIFF";
            GuardianCommandResult result = runner.run(List.of(
                    sayExecutable.toString(), "-v", systemVoice, "-r", Integer.toString(wordsPerMinute),
                    "--file-format=" + fileFormat, "--data-format=LEI16@22050",
                    "-o", output.toString(), "-f", input.toString()), timeout);
            if (result.timedOut()) throw new VoiceException(VoiceErrorCode.TIMEOUT,
                    "speech synthesis timed out");
            if (!result.successful()) throw new VoiceException(VoiceErrorCode.PROCESSING_FAILED,
                    "speech synthesis failed");
            long size = Files.size(output);
            if (size <= 0 || size > maxOutputBytes) throw new VoiceException(VoiceErrorCode.PROCESSING_FAILED,
                    "speech output exceeded the configured limit");
            return new VoiceAudio(Files.readAllBytes(output), normalizedFormat,
                    "wav".equals(normalizedFormat) ? "audio/wav" : "audio/aiff");
        } catch (VoiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new VoiceException(VoiceErrorCode.PROCESSING_FAILED,
                    "speech output could not be read");
        } finally {
            if (input != null) try { Files.deleteIfExists(input); } catch (IOException ignored) {}
            if (output != null) try { Files.deleteIfExists(output); } catch (IOException ignored) {}
        }
    }

    @Override public boolean available() { return Files.isExecutable(sayExecutable); }

    private String normalizeFormat(String value) {
        String format = Objects.requireNonNullElse(value, "wav").trim().toLowerCase(java.util.Locale.ROOT);
        if (!"wav".equals(format) && !"aiff".equals(format)) {
            throw new VoiceException(VoiceErrorCode.INVALID_REQUEST,
                    "response_format must be wav or aiff");
        }
        return format;
    }
}
