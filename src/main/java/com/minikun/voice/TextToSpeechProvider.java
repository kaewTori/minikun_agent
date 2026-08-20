package com.minikun.voice;

public interface TextToSpeechProvider {
    VoiceAudio synthesize(String text, String systemVoice, String format, double speed);
    boolean available();
}
