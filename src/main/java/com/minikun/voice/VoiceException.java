package com.minikun.voice;

public final class VoiceException extends RuntimeException {
    private final VoiceErrorCode code;

    public VoiceException(VoiceErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public VoiceErrorCode code() {
        return code;
    }
}
