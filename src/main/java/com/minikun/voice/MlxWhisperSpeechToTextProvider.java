package com.minikun.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.guardian.GuardianCommandResult;
import com.minikun.guardian.GuardianCommandRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Runs pinned MLX Whisper locally through fixed executables and a fixed Python adapter. */
public final class MlxWhisperSpeechToTextProvider implements SpeechToTextProvider {
    private final GuardianCommandRunner runner;
    private final ObjectMapper objectMapper;
    private final Path python;
    private final Path script;
    private final Path modelPath;
    private final Path afconvert;
    private final String modelName;
    private final Duration conversionTimeout;
    private final Duration transcriptionTimeout;

    public MlxWhisperSpeechToTextProvider(
            GuardianCommandRunner runner,
            ObjectMapper objectMapper,
            Path python,
            Path script,
            Path modelPath,
            Path afconvert,
            String modelName,
            Duration conversionTimeout,
            Duration transcriptionTimeout) {
        this.runner = Objects.requireNonNull(runner, "voice command runner must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "voice object mapper must not be null");
        this.python = absolute(python, "voice python path");
        this.script = absolute(script, "voice adapter path");
        this.modelPath = absolute(modelPath, "voice model path");
        this.afconvert = absolute(afconvert, "audio converter path");
        this.modelName = Objects.requireNonNullElse(modelName, "whisper-large-v3-turbo-q4");
        this.conversionTimeout = positive(conversionTimeout, "voice conversion timeout");
        this.transcriptionTimeout = positive(transcriptionTimeout, "voice transcription timeout");
    }

    @Override
    public VoiceTranscription transcribe(Path audioFile, String language, String prompt) {
        if (!available()) throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                "local Whisper runtime is not installed");
        Path normalized = null;
        try {
            normalized = Files.createTempFile("minikun-voice-normalized-", ".wav");
            GuardianCommandResult conversion = runner.run(List.of(
                    afconvert.toString(), "-f", "WAVE", "-d", "LEI16@16000", "-c", "1",
                    audioFile.toString(), normalized.toString()), conversionTimeout);
            requireSuccess(conversion, "audio conversion");

            List<String> command = new ArrayList<>(List.of(
                    python.toString(), script.toString(),
                    "--input", normalized.toString(),
                    "--model", modelPath.toString(),
                    "--language", normalizedLanguage(language)));
            if (prompt != null && !prompt.isBlank()) {
                command.add("--prompt");
                command.add(prompt.trim());
            }
            GuardianCommandResult result = runner.run(List.copyOf(command), transcriptionTimeout);
            requireSuccess(result, "speech transcription");
            JsonNode response = parseLastJsonObject(result.output());
            return new VoiceTranscription(
                    response.path("text").asText(""),
                    response.path("language").asText(normalizedLanguage(language)),
                    response.path("duration_seconds").asDouble(0));
        } catch (VoiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new VoiceException(VoiceErrorCode.PROCESSING_FAILED,
                    "temporary audio workspace is unavailable");
        } finally {
            if (normalized != null) try { Files.deleteIfExists(normalized); } catch (IOException ignored) {}
        }
    }

    @Override
    public boolean available() {
        return Files.isExecutable(python) && Files.isRegularFile(script)
                && Files.isDirectory(modelPath) && Files.isExecutable(afconvert);
    }

    @Override public String model() { return modelName; }

    private JsonNode parseLastJsonObject(String output) {
        String[] lines = Objects.requireNonNullElse(output, "").split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            String candidate = lines[index].trim();
            if (!candidate.startsWith("{") || !candidate.endsWith("}")) continue;
            try {
                return objectMapper.readTree(candidate);
            } catch (IOException ignored) {
                // Keep looking in case a dependency wrote a JSON diagnostic before the result.
            }
        }
        throw new VoiceException(VoiceErrorCode.PROCESSING_FAILED,
                "speech transcription returned an invalid response");
    }

    private void requireSuccess(GuardianCommandResult result, String operation) {
        if (result.successful()) return;
        if (result.timedOut()) throw new VoiceException(VoiceErrorCode.TIMEOUT, operation + " timed out");
        throw new VoiceException(VoiceErrorCode.PROCESSING_FAILED, operation + " failed");
    }

    private String normalizedLanguage(String language) {
        String value = Objects.requireNonNullElse(language, "auto").trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isBlank()) return "auto";
        if (!value.matches("auto|[a-z]{2,3}")) throw new VoiceException(VoiceErrorCode.INVALID_REQUEST,
                "language must be auto or an ISO language code");
        return value;
    }

    private Path absolute(Path value, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        if (!value.isAbsolute()) throw new IllegalArgumentException(label + " must be absolute");
        return value.normalize();
    }

    private Duration positive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return value;
    }
}
