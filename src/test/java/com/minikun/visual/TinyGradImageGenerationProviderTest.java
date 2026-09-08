package com.minikun.visual;

import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import java.net.ConnectException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TinyGradImageGenerationProviderTest {
    private static final String DEFAULT_NEGATIVE = "worst quality, bad anatomy, watermark";

    @Test
    void sendsStudioControlsToTinyGradAndReadsRawPng() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://127.0.0.1:8002");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        byte[] png = GeneratedImageStoreTest.png();
        String payload = """
                        {"prompt":"1girl reading by a window",
                         "negative_prompt":"low quality, text, worst quality, bad anatomy, watermark",
                         "adetailer":true,"face_prompts":["gentle smile"],"width":768,"height":1280,"steps":32,
                         "guidance":6.0,"scheduler":"dpmpp2m","schedule":"karras",
                         "long_prompt_mode":"chunk","seed":12345}
                        """;
        server.expect(requestTo("http://127.0.0.1:8002/generate"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(payload))
                .andRespond(withSuccess(png, MediaType.IMAGE_PNG));
        TinyGradImageGenerationProvider provider = provider(builder);

        GeneratedImage image = provider.generate(new ImageGenerationRequest(
                "1girl reading by a window", "low quality, text", List.of("gentle smile"),
                768, 1280, 32, 6.0, "dpmpp2m", "karras", 12345L));

        assertArrayEquals(png, image.bytes());
        assertEquals("mala-anime-mix-nsfw-ponyxl", image.provider());
        server.verify();
    }

    @Test
    void appliesDefaultsAndOmitsEmptyFacePromptsAndSeed() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://127.0.0.1:8002");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String payload = """
                        {"prompt":"portrait","negative_prompt":"worst quality, bad anatomy, watermark","adetailer":false,
                         "width":512,"height":768,"steps":40,"guidance":6.0,
                         "scheduler":"dpmpp2m","schedule":"karras","long_prompt_mode":"chunk"}
                        """;
        server.expect(requestTo("http://127.0.0.1:8002/generate"))
                .andExpect(content().json(payload))
                .andRespond(withSuccess(GeneratedImageStoreTest.png(), MediaType.IMAGE_PNG));
        TinyGradImageGenerationProvider provider = provider(builder);

        provider.generate("portrait");

        server.verify();
    }

    @Test
    void preservesTheCompleteLongPromptForChunkMode() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://127.0.0.1:8002");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String prompt = "score_9, score_8_up, (hero, lantern:1.3), detailed background, "
                + "cinematic lighting, final flourish, discarded detail, another detail";
        server.expect(requestTo("http://127.0.0.1:8002/generate"))
                .andExpect(content().json("""
                        {"prompt":"%s",
                         "long_prompt_mode":"chunk"}
                        """.formatted(prompt)))
                .andRespond(withSuccess(GeneratedImageStoreTest.png(), MediaType.IMAGE_PNG));

        GeneratedImage image = provider(builder).generate(prompt);

        assertEquals(prompt, image.effectivePrompt());
        assertEquals(DEFAULT_NEGATIVE, image.effectiveNegativePrompt());
        server.verify();
    }

    @Test
    void reportsAnOfflineServiceWithAnActionableError() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://127.0.0.1:8002")
                .requestFactory((uri, method) -> {
                    throw new ConnectException("Connection refused");
                });

        ImageGenerationException error = assertThrows(ImageGenerationException.class,
                () -> provider(builder).generate("portrait"));

        assertEquals(ImageGenerationException.Code.UNAVAILABLE, error.code());
        assertTrue(error.getMessage().contains("sdxl_server.py --serve 8002"));
    }

    private TinyGradImageGenerationProvider provider(RestClient.Builder builder) {
        return new TinyGradImageGenerationProvider(
                builder.build(), new ObjectMapper(), "http://127.0.0.1:8002",
                "mala-anime-mix-nsfw-ponyxl", DEFAULT_NEGATIVE,
                512, 768, 40, 6.0, "dpmpp2m", "karras", 1024);
    }
}
