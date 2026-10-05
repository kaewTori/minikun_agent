package com.minikun.voice;

public interface TextToSpeechProvider {
    VoiceAudio synthesize(String text, String systemVoice, String format, double speed);
    boolean available();

    /** Releases a persistent runtime; native per-request providers have nothing to retain. */
    default void releaseRuntime() {}
    default void resumeRuntime() {}
    default boolean running() { return false; }
}
