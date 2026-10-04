package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void transformsAConversationalBriefIntoAPonyPrompt() {
        ImageStudioController controller = new ImageStudioController(
                tool(prompt -> new GeneratedImage(GeneratedImageStoreTest.png(), "test-model"),
                        new GeneratedImageStore(directory, 1024, Clock.systemUTC())),
                () -> new TinyGradRuntimeStatusReader.RuntimeStatus(
                        false, "OFFLINE", "", 0,
                        new TinyGradRuntimeStatusReader.RuntimeMemory(-1, -1, -1), -1, -1,
                        -1, -1, false, null, null, null, 0, 0, Instant.EPOCH),
                brief -> "score_9, score_8_up, score_7_up, 1girl, black hair, observatory",
                "secret", 8000);

        ImageStudioController.PonyPromptResponse response = controller.transformPrompt(
                new ImageStudioController.PonyPromptRequest("ผู้หญิงผมดำในหอดูดาว"), "secret");

        assertEquals("score_9, score_8_up, score_7_up, 1girl, black hair, observatory", response.prompt());
        assertThrows(ResponseStatusException.class, () -> controller.transformPrompt(
                new ImageStudioController.PonyPromptRequest("scene"), "wrong"));
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

    @Test
    void keepsManualAndStoryPromptsInTheAuthenticatedStudioHistory() {
        InMemoryImageGenerationHistoryStore history = new InMemoryImageGenerationHistoryStore();
        GeneratedImageStore images = new GeneratedImageStore(directory, 1024, Clock.systemUTC());
        ImageGenerationTool imageTool = new ImageGenerationTool(
                prompt -> new GeneratedImage(GeneratedImageStoreTest.png(), "tinygrad-test"),
                images, history, 8000, 4_194_304L);
        ImageStudioController controller = new ImageStudioController(imageTool,
                () -> new TinyGradRuntimeStatusReader.RuntimeStatus(false, "OFFLINE", "", 0,
                        new TinyGradRuntimeStatusReader.RuntimeMemory(-1, -1, -1), -1, -1,
                        -1, -1, false, null, null, null, 0, 0, Instant.EPOCH),
                brief -> brief, history, "secret", 8000);

        controller.generate(new ImageStudioController.GenerationRequest(
                "1girl, observatory, moonlight", null, List.of(), null, null, null,
                null, null, null, null, "manual", null), "alice", "secret");
        controller.generate(new ImageStudioController.GenerationRequest(
                "score_9, floating city, night sky, wide shot", null, List.of(), null,
                null, null, null, null, null, null, "story", "เมืองลอยฟ้ายามค่ำ"),
                "alice", "secret");

        List<ImageStudioController.StudioHistoryItem> saved = controller.generations("alice", 50, "secret");
        assertEquals(2, saved.size());
        assertTrue(saved.stream().anyMatch(item -> item.mode().equals("manual")));
        assertTrue(saved.stream().anyMatch(item -> item.mode().equals("story")
                && item.title().equals("เมืองลอยฟ้ายามค่ำ")));
        assertTrue(saved.stream().allMatch(item -> item.review().score() > 0
                && !item.review().tip().isBlank()));
        assertEquals(List.of(), controller.generations("bob", 50, "secret"));
        assertThrows(ResponseStatusException.class,
                () -> controller.generations("alice", 50, "wrong"));
    }

    private ImageGenerationTool tool(StoryIllustrationProvider provider, GeneratedImageStore store) {
        return new ImageGenerationTool(provider, store, 8000, 4_194_304L);
    }
}
