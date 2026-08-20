package com.minikun.voice;

import java.util.Arrays;
import java.util.Objects;

public record VoiceAudio(byte[] data, String format, String mediaType) {
    public VoiceAudio {
        data = data == null ? new byte[0] : Arrays.copyOf(data, data.length);
        format = Objects.requireNonNullElse(format, "wav");
        mediaType = Objects.requireNonNullElse(mediaType, "audio/wav");
        if (data.length == 0) throw new VoiceException(VoiceErrorCode.PROCESSING_FAILED,
                "speech synthesis returned no audio");
    }

    @Override public byte[] data() { return Arrays.copyOf(data, data.length); }
}
