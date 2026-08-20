package com.minikun.voice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded voice orchestration. Uploaded and generated audio exists only in memory or temporary files. */
public final class VoiceService {
    private static final Logger LOG = LoggerFactory.getLogger(VoiceService.class);

    private final boolean enabled;
    private final SpeechToTextProvider speechToText;
    private final TextToSpeechProvider textToSpeech;
    private final VoiceAudioPolicy audioPolicy;
    private final Map<String, String> voices;
    private final String defaultVoice;
    private final int maxTextCharacters;
    private final int maxPromptCharacters;
    private final Semaphore transcriptionSlots;
    private final Semaphore synthesisSlots;

    public VoiceService(
            boolean enabled,
            SpeechToTextProvider speechToText,
            TextToSpeechProvider textToSpeech,
            VoiceAudioPolicy audioPolicy,
            Map<String, String> voices,
            String defaultVoice,
            int maxTextCharacters,
            int maxPromptCharacters,
            int maxConcurrentTranscriptions,
            int maxConcurrentSyntheses) {
        this.enabled = enabled;
        this.speechToText = Objects.requireNonNull(speechToText, "speech-to-text provider must not be null");
        this.textToSpeech = Objects.requireNonNull(textToSpeech, "text-to-speech provider must not be null");
        this.audioPolicy = Objects.requireNonNull(audioPolicy, "voice audio policy must not be null");
        this.voices = Map.copyOf(Objects.requireNonNull(voices, "voice allowlist must not be null"));
        this.defaultVoice = normalizeVoice(defaultVoice);
        if (!this.voices.containsKey(this.defaultVoice)) throw new IllegalArgumentException(
                "default voice must be present in the voice allowlist");
        if (maxTextCharacters < 1 || maxPromptCharacters < 0
                || maxConcurrentTranscriptions < 1 || maxConcurrentSyntheses < 1) {
            throw new IllegalArgumentException("voice limits must be positive");
        }
        this.maxTextCharacters = maxTextCharacters;
        this.maxPromptCharacters = maxPromptCharacters;
        this.transcriptionSlots = new Semaphore(maxConcurrentTranscriptions);
        this.synthesisSlots = new Semaphore(maxConcurrentSyntheses);
    }

    public VoiceTranscription transcribe(
            String filename, String contentType, byte[] audio, String language, String prompt) {
        requireEnabled();
        audioPolicy.validate(filename, contentType, audio);
        if (!speechToText.available()) throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                "local Whisper runtime is not installed");
        String safePrompt = Objects.requireNonNullElse(prompt, "").trim();
        if (safePrompt.length() > maxPromptCharacters) throw new VoiceException(VoiceErrorCode.INVALID_REQUEST,
                "transcription prompt is too long");
        if (!transcriptionSlots.tryAcquire()) throw new VoiceException(VoiceErrorCode.BUSY,
                "speech transcription is busy; try again shortly");
        Instant started = Instant.now();
        Path input = null;
        try {
            input = Files.createTempFile("minikun-voice-input-", suffix(filename));
            Files.write(input, audio);
            VoiceTranscription result = speechToText.transcribe(input, language, safePrompt);
            LOG.info("process=voice_transcription event=completed input_bytes={} text_characters={} duration_ms={} audio_stored=false",
                    audio.length, result.text().length(), Duration.between(started, Instant.now()).toMillis());
            return result;
        } catch (VoiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new VoiceException(VoiceErrorCode.PROCESSING_FAILED,
                    "temporary audio input could not be created");
        } finally {
            if (input != null) try { Files.deleteIfExists(input); } catch (IOException ignored) {}
            transcriptionSlots.release();
        }
    }

    public VoiceAudio synthesize(String text, String voice, String format, Double speed) {
        requireEnabled();
        String input = Objects.requireNonNullElse(text, "").trim();
        if (input.isBlank()) throw new VoiceException(VoiceErrorCode.INVALID_REQUEST,
                "speech input must not be blank");
        if (input.length() > maxTextCharacters) throw new VoiceException(VoiceErrorCode.INVALID_REQUEST,
                "speech input is too long");
        String alias = normalizeVoice(voice == null || voice.isBlank() ? defaultVoice : voice);
        String systemVoice = voices.get(alias);
        if (systemVoice == null) throw new VoiceException(VoiceErrorCode.INVALID_REQUEST,
                "voice is not allowlisted");
        double requestedSpeed = speed == null ? 1.0 : speed;
        if (!Double.isFinite(requestedSpeed) || requestedSpeed < 0.5 || requestedSpeed > 2.0) {
            throw new VoiceException(VoiceErrorCode.INVALID_REQUEST, "speed must be between 0.5 and 2.0");
        }
        if (!synthesisSlots.tryAcquire()) throw new VoiceException(VoiceErrorCode.BUSY,
                "speech synthesis is busy; try again shortly");
        Instant started = Instant.now();
        try {
            VoiceAudio result = textToSpeech.synthesize(input, systemVoice, format, requestedSpeed);
            LOG.info("process=voice_synthesis event=completed text_characters={} output_bytes={} duration_ms={} audio_stored=false",
                    input.length(), result.data().length, Duration.between(started, Instant.now()).toMillis());
            return result;
        } finally {
            synthesisSlots.release();
        }
    }

    public VoiceStatus status() {
        return new VoiceStatus(enabled, speechToText.available(), textToSpeech.available(), speechToText.model(),
                defaultVoice,
                voices.keySet().stream().sorted().toList(),
                audioPolicy.formats().stream().sorted().toList(),
                List.of("aiff", "wav"), audioPolicy.maxBytes(), maxTextCharacters, true, false);
    }

    private void requireEnabled() {
        if (!enabled) throw new VoiceException(VoiceErrorCode.UNAVAILABLE, "voice companion is disabled");
    }

    private String normalizeVoice(String value) {
        return Objects.requireNonNullElse(value, "").trim().toLowerCase(Locale.ROOT);
    }

    private String suffix(String filename) {
        String value = Objects.requireNonNullElse(filename, "audio.wav");
        int dot = value.lastIndexOf('.');
        String extension = dot < 0 ? "wav" : value.substring(dot + 1).toLowerCase(Locale.ROOT);
        return "." + extension.replaceAll("[^a-z0-9]", "");
    }

    public static Map<String, String> parseVoices(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : Objects.requireNonNullElse(value, "").split(",")) {
            if (entry.isBlank()) continue;
            String[] fields = entry.trim().split("=", 2);
            if (fields.length != 2 || fields[0].isBlank() || fields[1].isBlank()
                    || !fields[0].trim().matches("[a-zA-Z0-9_-]{1,40}")) {
                throw new IllegalArgumentException("voice allowlist must use alias=SystemVoice format");
            }
            result.put(fields[0].trim().toLowerCase(Locale.ROOT), fields[1].trim());
        }
        return result.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), Map::putAll);
    }
}
