package com.minikun.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.guardian.GuardianCommandRunner;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "minikun.voice.enabled", havingValue = "true", matchIfMissing = true)
public class VoiceConfiguration {
    @Bean
    SpeechToTextProvider speechToTextProvider(
            GuardianCommandRunner runner,
            ObjectMapper objectMapper,
            @Value("${minikun.voice.stt.python:${user.home}/Library/Application Support/Minikun/python/voice-venv/bin/python}") String python,
            @Value("${minikun.voice.stt.script:${user.home}/Library/Application Support/Minikun/python/voice/whisper_transcribe.py}") String script,
            @Value("${minikun.voice.stt.model-path:${user.home}/Library/Application Support/Minikun/models/whisper-large-v3-turbo-q4}") String modelPath,
            @Value("${minikun.voice.stt.afconvert:/usr/bin/afconvert}") String afconvert,
            @Value("${minikun.voice.stt.model:mlx-community/whisper-large-v3-turbo-q4}") String model,
            @Value("${minikun.voice.stt.conversion-timeout:30s}") Duration conversionTimeout,
            @Value("${minikun.voice.stt.timeout:180s}") Duration transcriptionTimeout) {
        return new MlxWhisperSpeechToTextProvider(runner, objectMapper,
                Path.of(python), Path.of(script), Path.of(modelPath), Path.of(afconvert), model,
                conversionTimeout, transcriptionTimeout);
    }

    @Bean
    TextToSpeechProvider textToSpeechProvider(
            GuardianCommandRunner runner,
            @Value("${minikun.voice.tts.say:/usr/bin/say}") String say,
            @Value("${minikun.voice.tts.timeout:30s}") Duration timeout,
            @Value("${minikun.voice.max-output-bytes:8388608}") long maxOutputBytes) {
        return new MacOsSayTextToSpeechProvider(runner, Path.of(say), timeout, maxOutputBytes);
    }

    @Bean
    VoiceService voiceService(
            SpeechToTextProvider speechToText,
            TextToSpeechProvider textToSpeech,
            @Value("${minikun.voice.max-input-bytes:10485760}") long maxInputBytes,
            @Value("${minikun.voice.voices:minikun=Kanya,kanya=Kanya}") String voices,
            @Value("${minikun.voice.default-voice:minikun}") String defaultVoice,
            @Value("${minikun.voice.max-text-characters:4000}") int maxTextCharacters,
            @Value("${minikun.voice.max-prompt-characters:500}") int maxPromptCharacters,
            @Value("${minikun.voice.max-concurrent-transcriptions:1}") int maxTranscriptions,
            @Value("${minikun.voice.max-concurrent-syntheses:2}") int maxSyntheses) {
        return new VoiceService(true, speechToText, textToSpeech, new VoiceAudioPolicy(maxInputBytes),
                VoiceService.parseVoices(voices), defaultVoice, maxTextCharacters, maxPromptCharacters,
                maxTranscriptions, maxSyntheses);
    }

    @Bean
    VoiceHealthIndicator voiceHealthIndicator(VoiceService voiceService) {
        return new VoiceHealthIndicator(voiceService);
    }
}
