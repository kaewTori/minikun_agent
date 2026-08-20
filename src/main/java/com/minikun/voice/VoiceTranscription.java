package com.minikun.voice;

import java.util.Objects;

public record VoiceTranscription(String text, String language, double durationSeconds) {
    public VoiceTranscription {
        text = Objects.requireNonNullElse(text, "").trim();
        language = Objects.requireNonNullElse(language, "unknown").trim();
        if (text.isBlank()) throw new VoiceException(VoiceErrorCode.PROCESSING_FAILED,
                "speech transcription returned no text");
        if (!Double.isFinite(durationSeconds) || durationSeconds < 0) durationSeconds = 0;
    }
}
