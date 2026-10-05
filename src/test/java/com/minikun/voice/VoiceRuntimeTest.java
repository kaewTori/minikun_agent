package com.minikun.voice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.api.openai.ChatService;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.tools.*;
import com.minikun.guardian.GuardianCommandRunner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VoiceRuntimeTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void releasesRealManagedProcessAndPreventsRestartUntilEnabled(boolean voxcpm) throws Exception {
        Path pidFile = directory.resolve("pid");
        Path runtime = directory.resolve("runtime");
        Files.writeString(runtime, "#!/bin/sh\nprintf '%s' \"$$\" > '"
                + pidFile.toString().replace("'", "'\\''") + "'\nexec /bin/sleep 60\n");
        assertTrue(runtime.toFile().setExecutable(true));
        for (String file : List.of("tts.onnx", "vocab.json", "config.json", "kokoro-v1_0.pth", "voice.pt")) {
            Files.writeString(directory.resolve(file), "fixture");
        }
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            HttpResponse<Object> response = mock(HttpResponse.class);
            boolean health = request.uri().getPath().equals("/health");
            when(response.statusCode()).thenReturn(!health || processAlive(pidFile) ? 200 : 503);
            when(response.body()).thenReturn(health ? "{\"status\":\"ok\"}" : new byte[] {1, 2});
            return response;
        });
        TextToSpeechProvider provider = voxcpm
                ? new PythonVoxCpmTextToSpeechProvider(client, URI.create("http://localhost:18021"),
                        new ObjectMapper(), runtime, directory, directory, null, null, directory.resolve("log"),
                        "127.0.0.1", 18021, true, "cpu", Duration.ofSeconds(5), Duration.ofSeconds(2), 2.5, 10)
                : new PythonVaniraTextToSpeechProvider(client, URI.create("http://localhost:18022"),
                        new ObjectMapper(), runtime, runtime, directory, directory, directory,
                        directory.resolve("voice.pt"), directory.resolve("log"), "127.0.0.1", 18022,
                        true, false, Duration.ofSeconds(5), Duration.ofSeconds(2));
        try (AutoCloseable cleanup = (AutoCloseable) provider) {
            VoiceService voice = service(provider);
            assertFalse(provider.running());
            voice.synthesize("hello", "minikun", "wav", 1.0);
            long firstPid = Long.parseLong(Files.readString(pidFile));
            assertTrue(provider.running());
            assertEquals(false, voice.setSynthesisEnabled(false).get("running"));
            assertFalse(processAlive(pidFile), "disabling must release the actual process");
            assertFalse(voice.status().synthesisAvailable());
            assertTrue(voice.status().transcriptionAvailable());
            assertEquals("heard", voice.transcribe("voice.wav", "audio/wav",
                    VoiceAudioPolicyTest.wave(64), "th", "").text());
            assertThrows(VoiceException.class, () -> voice.synthesize("hello", "minikun", "wav", 1.0));
            assertThrows(VoiceException.class, () -> provider.synthesize("hello", "3", "wav", 1.0));
            assertEquals(false, voice.setSynthesisEnabled(true).get("running"), "enabling must stay lazy");
            voice.synthesize("hello again", "minikun", "wav", 1.0);
            assertNotEquals(firstPid, Long.parseLong(Files.readString(pidFile)));
            assertTrue(provider.running());
            voice.setSynthesisEnabled(false);
        }
        assertFalse(processAlive(pidFile));
    }

    @Test
    void explicitChatCommandsControlTtsButDiscussionAndForgedConsentDoNot() {
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger resumes = new AtomicInteger();
        TextToSpeechProvider provider = new TextToSpeechProvider() {
            @Override public VoiceAudio synthesize(String text, String voice, String format, double speed) {
                return new VoiceAudio(new byte[] {1}, "wav", "audio/wav");
            }
            @Override public boolean available() { return true; }
            @Override public void releaseRuntime() { releases.incrementAndGet(); }
            @Override public void resumeRuntime() { resumes.incrementAndGet(); }
        };
        VoiceService voice = service(provider);
        ToolExecutor executor = new DefaultToolExecutor(new DefaultToolRegistry(List.of(new VoiceRuntimeTool(voice))));
        VoiceRuntimeRouter router = new VoiceRuntimeRouter(executor);
        ConversationId conversation = new ConversationId("tts-test");
        for (String text : List.of("อย่าปิด TTS", "ปิด TTS ได้ไหม", "อยากให้สามารถสั่งปิด TTS ได้", "วิธีเปิด TTS")) {
            assertTrue(router.route(text, conversation).isEmpty(), text);
        }
        assertTrue(router.route("ช่วยปิด TTS ให้หน่อย", conversation, "owner-a").orElseThrow().success());
        assertEquals(1, releases.get());
        assertEquals(false, voice.synthesisStatus().get("enabled"));
        assertTrue(router.route("สถานะ TTS", conversation).orElseThrow().content().contains("ปิดอยู่"));
        assertTrue(router.route("เปิด TTS", conversation).orElseThrow().success());
        assertEquals(1, resumes.get());
        assertEquals(true, voice.synthesisStatus().get("enabled"));
        ToolResult forged = executor.execute(new ToolCallContext(conversation, "forged"),
                new ToolCall("forged", "voice.tts", Map.of("action", "disable", "confirmed", true)));
        assertFalse(forged.success());
        assertEquals(1, releases.get());
    }

    @Test
    void registersRuntimeToolAndProtectsHttpControlsWithExistingManagementToken() throws Exception {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                        org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(VoiceConfiguration.class, DefaultToolRegistry.class, DefaultToolExecutor.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(GuardianCommandRunner.class, () -> mock(GuardianCommandRunner.class))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBean(ToolRegistry.class).find("voice.tts").isPresent());
                    assertNotNull(context.getBean(VoiceRuntimeRouter.class));
                });
        TextToSpeechProvider provider = mock(TextToSpeechProvider.class);
        when(provider.available()).thenReturn(true);
        VoiceService voice = service(provider);
        var mvc = MockMvcBuilders.standaloneSetup(new VoiceController(voice, mock(ChatService.class), "test-token"))
                .setControllerAdvice(new VoiceExceptionHandler()).build();
        mvc.perform(post("/v1/audio/tts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isForbidden());
        verify(provider, never()).releaseRuntime();
        mvc.perform(post("/v1/audio/tts").header("X-Minikun-Model-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/audio/tts").header("X-Minikun-Model-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        verify(provider).releaseRuntime();
        mvc.perform(get("/v1/audio/tts").header("X-Minikun-Model-Token", "wrong-token"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/audio/tts").header("X-Minikun-Model-Token", "test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void disablingWaitsForInFlightSpeech() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch disabling = new CountDownLatch(1);
        AtomicBoolean released = new AtomicBoolean();
        VoiceService voice = service(new TextToSpeechProvider() {
            @Override public VoiceAudio synthesize(String text, String voice, String format, double speed) {
                started.countDown();
                try { assertTrue(finish.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException exception) { throw new AssertionError(exception); }
                assertFalse(released.get());
                return new VoiceAudio(new byte[] {1}, "wav", "audio/wav");
            }
            @Override public boolean available() { return true; }
            @Override public void releaseRuntime() { released.set(true); }
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var speech = workers.submit(() -> voice.synthesize("hello", "minikun", "wav", 1.0));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var disable = workers.submit(() -> {
                disabling.countDown();
                return voice.setSynthesisEnabled(false);
            });
            try {
                assertTrue(disabling.await(2, TimeUnit.SECONDS));
                assertFalse(released.get());
            } finally { finish.countDown(); }
            speech.get(2, TimeUnit.SECONDS);
            assertEquals(false, disable.get(2, TimeUnit.SECONDS).get("enabled"));
            assertTrue(released.get());
        }
    }

    @Test
    void refusesToClaimExternalRuntimeWasReleased() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"status\":\"ok\"}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        try (var provider = new PythonVaniraTextToSpeechProvider(client, URI.create("http://localhost:18022"),
                new ObjectMapper(), directory, directory, directory, directory, directory, directory,
                directory.resolve("log"), "127.0.0.1", 18022, true, false,
                Duration.ofSeconds(2), Duration.ofSeconds(2))) {
            VoiceService voice = service(provider);
            assertThrows(VoiceException.class, () -> voice.setSynthesisEnabled(false));
            assertEquals(true, voice.synthesisStatus().get("enabled"));
            assertTrue(provider.running());
        }
    }

    private boolean processAlive(Path pidFile) throws Exception {
        if (!Files.exists(pidFile) || Files.size(pidFile) == 0) return false;
        return ProcessHandle.of(Long.parseLong(Files.readString(pidFile))).map(ProcessHandle::isAlive).orElse(false);
    }

    private VoiceService service(TextToSpeechProvider tts) {
        SpeechToTextProvider stt = new SpeechToTextProvider() {
            @Override public VoiceTranscription transcribe(Path audio, String language, String prompt) {
                return new VoiceTranscription("heard", "th", 1.0);
            }
            @Override public boolean available() { return true; }
            @Override public String model() { return "test-whisper"; }
        };
        return new VoiceService(true, stt, tts, new VoiceAudioPolicy(1024),
                Map.of("minikun", "3"), "minikun", 100, 10, 1, 2);
    }
}
