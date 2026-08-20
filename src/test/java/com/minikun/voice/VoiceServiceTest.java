package com.minikun.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VoiceServiceTest {
    @Test
    void transcribesAndSynthesizesWithoutRetainingInputFiles() {
        AtomicReference<Path> inputPath = new AtomicReference<>();
        SpeechToTextProvider stt = new SpeechToTextProvider() {
            @Override public VoiceTranscription transcribe(Path audioFile, String language, String prompt) {
                inputPath.set(audioFile);
                assertTrue(Files.exists(audioFile));
                return new VoiceTranscription("สวัสดีมินิคุง", "th", 1.0);
            }
            @Override public boolean available() { return true; }
            @Override public String model() { return "local-whisper"; }
        };
        AtomicReference<String> systemVoice = new AtomicReference<>();
        TextToSpeechProvider tts = new TextToSpeechProvider() {
            @Override public VoiceAudio synthesize(String text, String voice, String format, double speed) {
                systemVoice.set(voice);
                return new VoiceAudio(new byte[] {1, 2, 3}, "wav", "audio/wav");
            }
            @Override public boolean available() { return true; }
        };
        VoiceService service = service(stt, tts);

        VoiceTranscription transcript = service.transcribe(
                "voice.wav", "audio/wav", VoiceAudioPolicyTest.wave(64), "th", "");
        VoiceAudio audio = service.synthesize("ได้เลยครับ", "minikun", "wav", 1.0);

        assertEquals("สวัสดีมินิคุง", transcript.text());
        assertEquals(3, audio.data().length);
        assertEquals("Kanya", systemVoice.get());
        assertFalse(Files.exists(inputPath.get()), "uploaded temporary audio must be deleted");
        assertFalse(service.status().storesAudio());
        assertTrue(service.status().localOnly());
    }

    @Test
    void rejectsUnknownVoiceAndExcessiveTextBeforeProviderExecution() {
        SpeechToTextProvider stt = new UnavailableStt();
        TextToSpeechProvider tts = new TextToSpeechProvider() {
            @Override public VoiceAudio synthesize(String text, String voice, String format, double speed) {
                throw new AssertionError("provider must not run");
            }
            @Override public boolean available() { return true; }
        };
        VoiceService service = service(stt, tts);

        assertThrows(VoiceException.class, () -> service.synthesize("hello", "unknown", "wav", 1.0));
        assertThrows(VoiceException.class, () -> service.synthesize("x".repeat(101), "minikun", "wav", 1.0));
        assertThrows(VoiceException.class, () -> service.synthesize("hello", "minikun", "wav", 3.0));
    }

    private VoiceService service(SpeechToTextProvider stt, TextToSpeechProvider tts) {
        return new VoiceService(true, stt, tts, new VoiceAudioPolicy(1024),
                Map.of("minikun", "Kanya"), "minikun", 100, 20, 1, 1);
    }

    private static final class UnavailableStt implements SpeechToTextProvider {
        @Override public VoiceTranscription transcribe(Path audioFile, String language, String prompt) {
            throw new AssertionError("provider must not run");
        }
        @Override public boolean available() { return false; }
        @Override public String model() { return "none"; }
    }
}
