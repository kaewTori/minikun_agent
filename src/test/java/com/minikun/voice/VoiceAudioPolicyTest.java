package com.minikun.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VoiceAudioPolicyTest {
    private final VoiceAudioPolicy policy = new VoiceAudioPolicy(1024);

    @Test
    void acceptsMatchingWaveAndRejectsSpoofedOrOversizedFiles() {
        byte[] wave = wave(64);

        assertEquals("wav", policy.validate("voice.wav", "audio/wav", wave));
        assertThrows(VoiceException.class,
                () -> policy.validate("voice.wav", "audio/wav", "not audio".getBytes()));
        assertThrows(VoiceException.class,
                () -> policy.validate("voice.wav", "audio/mpeg", wave));
        assertThrows(VoiceException.class,
                () -> policy.validate("voice.exe", "application/octet-stream", wave));
        assertThrows(VoiceException.class,
                () -> policy.validate("voice.wav", "audio/wav", wave(2048)));
    }

    static byte[] wave(int size) {
        byte[] result = new byte[Math.max(size, 12)];
        System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, result, 0, 4);
        System.arraycopy("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, result, 8, 4);
        return result;
    }
}
