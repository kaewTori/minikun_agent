package com.minikun.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class VoiceHealthIndicatorTest {
    @Test
    void reportsOptionalProviderReadinessWithoutTakingApplicationDown() {
        SpeechToTextProvider stt = new SpeechToTextProvider() {
            @Override public VoiceTranscription transcribe(Path audioFile, String language, String prompt) {
                throw new UnsupportedOperationException();
            }
            @Override public boolean available() { return false; }
            @Override public String model() { return "local"; }
        };
        TextToSpeechProvider tts = new TextToSpeechProvider() {
            @Override public VoiceAudio synthesize(String text, String systemVoice, String format, double speed) {
                throw new UnsupportedOperationException();
            }
            @Override public boolean available() { return true; }
        };
        VoiceService service = new VoiceService(true, stt, tts, new VoiceAudioPolicy(1024),
                Map.of("minikun", "Kanya"), "minikun", 100, 10, 1, 1);

        var health = new VoiceHealthIndicator(service).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("UNAVAILABLE", health.getDetails().get("transcription"));
        assertEquals("READY", health.getDetails().get("synthesis"));
        assertEquals(false, health.getDetails().get("storesAudio"));
    }
}
