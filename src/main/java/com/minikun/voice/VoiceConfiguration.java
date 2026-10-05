package com.minikun.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.guardian.GuardianCommandRunner;
import java.net.URI;
import java.net.http.HttpClient;
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
    VoiceRuntimeTool voiceRuntimeTool(VoiceService voice) { return new VoiceRuntimeTool(voice); }

    @Bean
    VoiceRuntimeRouter voiceRuntimeRouter(com.minikun.tools.ToolExecutor executor) {
        return new VoiceRuntimeRouter(executor);
    }

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
    @ConditionalOnProperty(name = "minikun.voice.tts.provider", havingValue = "macos", matchIfMissing = true)
    TextToSpeechProvider textToSpeechProvider(
            GuardianCommandRunner runner,
            @Value("${minikun.voice.tts.say:/usr/bin/say}") String say,
            @Value("${minikun.voice.tts.timeout:30s}") Duration timeout,
            @Value("${minikun.voice.max-output-bytes:8388608}") long maxOutputBytes) {
        return new MacOsSayTextToSpeechProvider(runner, Path.of(say), timeout, maxOutputBytes);
    }

    @Bean(initMethod = "warmup", destroyMethod = "close")
    @ConditionalOnProperty(name = "minikun.voice.tts.provider", havingValue = "voxcpm")
    PythonVoxCpmTextToSpeechProvider voxcpmTextToSpeechProvider(
            ObjectMapper objectMapper,
            @Value("${minikun.voice.tts.host:127.0.0.1}") String host,
            @Value("${minikun.voice.tts.port:18021}") int port,
            @Value("${minikun.voice.tts.python:/Volumes/minikun/homelab/tts/venv/bin/python}") String python,
            @Value("${minikun.voice.tts.working-directory:/Volumes/minikun/homelab/tts/siangtts}") String workingDirectory,
            @Value("${minikun.voice.tts.base-model:/Volumes/minikun/homelab/tts/models/VoxCPM2}") String baseModel,
            @Value("${minikun.voice.tts.adapter:/Volumes/minikun/homelab/tts/models/SiangTTS-VoxCPM2-Thai-LoRA}") String adapter,
            @Value("${minikun.voice.tts.hf-home:/Volumes/minikun/homelab/tts/cache/huggingface}") String hfHome,
            @Value("${minikun.voice.tts.log:/Volumes/minikun/homelab/tts/logs/siangtts.log}") String logFile,
            @Value("${minikun.voice.tts.auto-start:true}") boolean autoStart,
            @Value("${minikun.voice.tts.device:auto}") String device,
            @Value("${minikun.voice.tts.startup-timeout:180s}") Duration startupTimeout,
            @Value("${minikun.voice.tts.timeout:180s}") Duration requestTimeout,
            @Value("${minikun.voice.tts.cfg-value:2.5}") double cfgValue,
            @Value("${minikun.voice.tts.timesteps:10}") int timesteps) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();
        return new PythonVoxCpmTextToSpeechProvider(httpClient, URI.create("http://" + host + ":" + port), objectMapper,
                Path.of(python), Path.of(workingDirectory), Path.of(baseModel),
                adapter.isBlank() ? null : Path.of(adapter),
                hfHome.isBlank() ? null : Path.of(hfHome), Path.of(logFile),
                host, port, autoStart, device, startupTimeout, requestTimeout, cfgValue, timesteps);
    }

    @Bean(initMethod = "warmup", destroyMethod = "close")
    @ConditionalOnProperty(name = "minikun.voice.tts.provider", havingValue = "vaniratts")
    PythonVaniraTextToSpeechProvider vaniraTextToSpeechProvider(
            ObjectMapper objectMapper,
            @Value("${minikun.voice.tts.vanira.host:127.0.0.1}") String host,
            @Value("${minikun.voice.tts.vanira.port:18022}") int port,
            @Value("${minikun.voice.tts.vanira.python:/Volumes/minikun/homelab/tts/venv/bin/python}") String python,
            @Value("${minikun.voice.tts.vanira.server-script:${user.dir}/src/main/python/vanira_server.py}") String serverScript,
            @Value("${minikun.voice.tts.vanira.working-directory:/Volumes/minikun/homelab/tts}") String workingDirectory,
            @Value("${minikun.voice.tts.vanira.model-path:/Volumes/minikun/homelab/tts/models/VaniraTTS}") String modelPath,
            @Value("${minikun.voice.tts.kokoro.model-path:/Volumes/minikun/homelab/tts/models/Kokoro-82M}") String kokoroModelPath,
            @Value("${minikun.voice.tts.kokoro.voice:/Volumes/minikun/homelab/tts/models/Kokoro-82M/voices/am_michael.pt}") String kokoroVoice,
            @Value("${minikun.voice.tts.vanira.log:/Volumes/minikun/homelab/tts/logs/vaniratts.log}") String logFile,
            @Value("${minikun.voice.tts.vanira.auto-start:true}") boolean autoStart,
            @Value("${minikun.voice.tts.vanira.warmup:false}") boolean warmup,
            @Value("${minikun.voice.tts.vanira.startup-timeout:60s}") Duration startupTimeout,
            @Value("${minikun.voice.tts.vanira.timeout:60s}") Duration requestTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();
        return new PythonVaniraTextToSpeechProvider(httpClient, URI.create("http://" + host + ":" + port),
                objectMapper, Path.of(python), Path.of(serverScript), Path.of(workingDirectory), Path.of(modelPath),
                Path.of(kokoroModelPath), Path.of(kokoroVoice),
                Path.of(logFile), host, port, autoStart, warmup, startupTimeout, requestTimeout);
    }

    @Bean
    VoiceService voiceService(
            SpeechToTextProvider speechToText,
            TextToSpeechProvider textToSpeech,
            @Value("${minikun.voice.max-input-bytes:10485760}") long maxInputBytes,
            @Value("${minikun.voice.voices:minikun=3,male=3,male2=4}") String voices,
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
