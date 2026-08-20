package com.minikun.voice;

import java.nio.file.Path;

public interface SpeechToTextProvider {
    VoiceTranscription transcribe(Path audioFile, String language, String prompt);
    boolean available();
    String model();
}
