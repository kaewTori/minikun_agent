package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class ImageStudioControllerTest {
    @TempDir java.nio.file.Path directory;

    @Test
    void generatesAndStoresAnImageForTheStudio() {
        Instant now = Instant.parse("2026-08-31T10:15:30Z");
        GeneratedImageStore store = new GeneratedImageStore(
                directory, 1024, Clock.fixed(now, ZoneOffset.UTC));
        ImageStudioController controller = new ImageStudioController(
                tool(prompt -> new GeneratedImage(GeneratedImageStoreTest.png(), "test-model"), store),
                "secret", 8000);

        ImageStudioController.GenerationResponse response = controller.generate(
                new ImageStudioController.GenerationRequest("Moonlit portrait"), "secret");

        assertEquals("score_9, score_8_up, score_7_up, Moonlit portrait", response.prompt());
        org.junit.jupiter.api.Assertions.assertTrue(response.seed() >= 0L);
        org.junit.jupiter.api.Assertions.assertFalse(response.generation_id().isBlank());
        assertEquals("test-model", response.provider());
        assertEquals(now, response.created_at());
        assertEquals(GeneratedImageStoreTest.png().length,
                store.read(response.url().substring(response.url().lastIndexOf('/') + 1)).bytes().length);
    }

    @Test
    void rejectsAnInvalidPersonalToken() {
        ImageStudioController controller = new ImageStudioController(
                tool(prompt -> new GeneratedImage(GeneratedImageStoreTest.png(), "test-model"),
                        new GeneratedImageStore(directory, 1024, Clock.systemUTC())),
                "secret", 8000);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                controller.generate(new ImageStudioController.GenerationRequest("portrait"), "wrong"));

        assertEquals(403, error.getStatusCode().value());
    }

    @Test
    void exposesTheAuthenticatedTinyGradRuntimeStatus() {
        TinyGradRuntimeStatusReader.RuntimeStatus expected = new TinyGradRuntimeStatusReader.RuntimeStatus(
                true, "ONLINE", "http://127.0.0.1:8002/health", 3,
                new TinyGradRuntimeStatusReader.RuntimeMemory(10, 0, 10), 20, 50.0,
                2, 50, false, null, "768x1280", 1, 1, 4, Instant.now());
        ImageStudioController controller = new ImageStudioController(
                tool(prompt -> new GeneratedImage(GeneratedImageStoreTest.png(), "test-model"),
                        new GeneratedImageStore(directory, 1024, Clock.systemUTC())),
                () -> expected, "secret", 8000);

        assertEquals(expected, controller.status("secret"));
        assertThrows(ResponseStatusException.class, () -> controller.status("wrong"));
    }

    @Test
    void passesAdvancedStudioControlsToTheProvider() {
        AtomicReference<ImageGenerationRequest> captured = new AtomicReference<>();
        StoryIllustrationProvider provider = new StoryIllustrationProvider() {
            @Override public GeneratedImage generate(String prompt) {
                throw new AssertionError("studio should use the structured request");
            }
            @Override public GeneratedImage generate(ImageGenerationRequest request) {
                captured.set(request);
                return new GeneratedImage(GeneratedImageStoreTest.png(), "tinygrad-test");
            }
        };
        ImageStudioController controller = new ImageStudioController(
                tool(provider, new GeneratedImageStore(directory, 1024, Clock.systemUTC())), "", 8000);

        controller.generate(new ImageStudioController.GenerationRequest(
                "portrait", "bad hands", List.of("soft smile"), 768, 1280,
                32, 6.0, "dpmpp2m", "karras", 77L), null);

        assertEquals("bad hands", captured.get().negativePrompt());
        assertEquals(List.of("soft smile"), captured.get().facePrompts());
        assertEquals(768, captured.get().width());
        assertEquals(1280, captured.get().height());
        assertEquals(77L, captured.get().seed());
    }

    private ImageGenerationTool tool(StoryIllustrationProvider provider, GeneratedImageStore store) {
        return new ImageGenerationTool(provider, store, 8000, 4_194_304L);
    }
}
