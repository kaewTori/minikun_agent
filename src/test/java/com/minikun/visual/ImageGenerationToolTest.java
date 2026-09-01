package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.tools.ToolCallContext;
import com.minikun.tools.ToolErrorCode;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageGenerationToolTest {
    private static final ToolCallContext CONTEXT = new ToolCallContext(
            new ConversationId("image-tool"), "call-1");

    @TempDir Path directory;

    @Test
    void generatesStoresAndReturnsALocalImageUrl() {
        AtomicReference<ImageGenerationRequest> captured = new AtomicReference<>();
        StoryIllustrationProvider provider = new StoryIllustrationProvider() {
            @Override public GeneratedImage generate(String prompt) {
                throw new AssertionError("tool should use the structured request");
            }

            @Override public GeneratedImage generate(ImageGenerationRequest request) {
                captured.set(request);
                return new GeneratedImage(GeneratedImageStoreTest.png(), "tinygrad-test");
            }
        };
        ImageGenerationTool tool = tool(provider);

        var result = tool.execute(CONTEXT, Map.of(
                "prompt", "moonlit anime portrait",
                "negative_prompt", "text, watermark",
                "width", 768,
                "height", 1280,
                "steps", 32,
                "seed", 77));

        assertTrue(result.success());
        Map<?, ?> value = (Map<?, ?>) result.value();
        assertTrue(value.get("url").toString().startsWith("/v1/images/generated/"));
        assertEquals("tinygrad-test", value.get("provider"));
        assertEquals(768, captured.get().width());
        assertEquals(1280, captured.get().height());
        assertEquals(77L, captured.get().seed());
        assertTrue(captured.get().prompt().startsWith(
                "score_9, score_8_up, score_7_up, "));
        assertFalse(captured.get().prompt().contains("source_anime"));
        assertFalse(tool.requiresExplicitConfirmation(Map.of()));
    }

    @Test
    void protectsAnimalOnlyPonyScenesFromCharacterBiasedLoras() {
        AtomicReference<ImageGenerationRequest> captured = new AtomicReference<>();
        ImageGenerationTool tool = tool(new StoryIllustrationProvider() {
            @Override public GeneratedImage generate(String prompt) {
                throw new AssertionError("tool should use the structured request");
            }

            @Override public GeneratedImage generate(ImageGenerationRequest request) {
                captured.set(request);
                return new GeneratedImage(GeneratedImageStoreTest.png(), "tinygrad-test");
            }
        });

        tool.generate(ImageGenerationRequest.promptOnly(
                "black cat, old observatory, glowing starlight"));

        assertTrue(captured.get().prompt().contains("animal focus, no humans"));
        assertTrue(captured.get().negativePrompt().contains("1girl"));
    }

    @Test
    void rejectsInvalidDimensionsBeforeCallingTinyGrad() {
        ImageGenerationTool tool = tool(prompt -> {
            throw new AssertionError("invalid requests must not reach the provider");
        });

        var result = tool.execute(CONTEXT, Map.of(
                "prompt", "portrait",
                "width", 750,
                "height", 1280));

        assertFalse(result.success());
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, result.errorCode());
    }

    @Test
    void advertisesTheNativeImageToolName() {
        ImageGenerationTool tool = tool(prompt ->
                new GeneratedImage(GeneratedImageStoreTest.png(), "tinygrad-test"));

        assertEquals("image.generate", tool.definition().name());
        assertTrue(tool.definition().parameters().get("prompt").required());
    }

    @Test
    void assignsTheActualSeedAndStoresScopedReproductionHistory() {
        InMemoryImageGenerationHistoryStore history = new InMemoryImageGenerationHistoryStore();
        AtomicReference<ImageGenerationRequest> captured = new AtomicReference<>();
        StoryIllustrationProvider provider = new StoryIllustrationProvider() {
            @Override public GeneratedImage generate(String prompt) { throw new AssertionError(); }
            @Override public GeneratedImage generate(ImageGenerationRequest request) {
                captured.set(request);
                return new GeneratedImage(GeneratedImageStoreTest.png(), "pony-local");
            }
        };
        ImageGenerationTool tool = new ImageGenerationTool(
                provider, new GeneratedImageStore(directory, 1024, Clock.systemUTC()),
                history, 8000, 4_194_304L);

        ImageGenerationTool.Generation generated = tool.generate(
                ImageGenerationRequest.promptOnly("moonlit observatory"),
                new ImageGenerationScope("kaew", "story-42", "generated", "cover", "ดาวเหนือ"));

        assertEquals(captured.get().seed(), generated.seed());
        assertTrue(generated.seed() >= 0L && generated.seed() <= 0xffff_ffffL);
        assertEquals(captured.get().prompt(), generated.prompt());
        var saved = history.find("kaew", "story-42", 10).getFirst();
        assertEquals(generated.historyId(), saved.id());
        assertEquals(generated.prompt(), saved.prompt());
        assertEquals(generated.seed(), saved.seed());
        assertEquals("cover", saved.illustrationMode());
        assertEquals("ดาวเหนือ", saved.sceneTitle());
    }

    private ImageGenerationTool tool(StoryIllustrationProvider provider) {
        return new ImageGenerationTool(
                provider,
                new GeneratedImageStore(directory, 1024, Clock.systemUTC()),
                8000,
                4_194_304L);
    }
}
