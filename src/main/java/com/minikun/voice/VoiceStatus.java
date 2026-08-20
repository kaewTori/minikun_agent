package com.minikun.voice;

import java.util.List;

public record VoiceStatus(
        boolean enabled,
        boolean transcriptionAvailable,
        boolean synthesisAvailable,
        String transcriptionModel,
        String defaultVoice,
        List<String> voices,
        List<String> inputFormats,
        List<String> outputFormats,
        long maxInputBytes,
        int maxTextCharacters,
        boolean localOnly,
        boolean storesAudio) {
    public VoiceStatus {
        voices = voices == null ? List.of() : List.copyOf(voices);
        inputFormats = inputFormats == null ? List.of() : List.copyOf(inputFormats);
        outputFormats = outputFormats == null ? List.of() : List.copyOf(outputFormats);
    }
}
