package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoryIllustrationServiceTest {
    @TempDir Path directory;

    @Test
    void buildsAScenePromptAndReturnsGeneratedAttachment() {
        AtomicReference<String> prompt = new AtomicReference<>();
        StoryIllustrationProvider provider = value -> {
            prompt.set(value);
            return new GeneratedImage(GeneratedImageStoreTest.png(), "test-image-model");
        };
        StoryIllustrationService service = new StoryIllustrationService(
                tool(provider), true, 2000);

        var result = service.illustrate(
                "แต่งเรื่องสั้นเกี่ยวกับแมวที่ตามหาดวงดาว",
                "ในคืนที่เงียบงัน มะลิปีนขึ้นไปถึงหลังคาหอดูดาวและพบแสงที่ตามหา");
        var attachments = result.attachments();

        assertEquals(1, attachments.size());
        assertTrue(result.notice().isEmpty());
        assertEquals("generated", attachments.getFirst().origin());
        assertEquals("test-image-model", attachments.getFirst().provider());
        assertTrue(attachments.getFirst().url().startsWith("/v1/images/generated/"));
        assertEquals(prompt.get(), attachments.getFirst().prompt());
        assertTrue(attachments.getFirst().seed() >= 0L);
        assertFalse(attachments.getFirst().generationId().isBlank());
        assertTrue(prompt.get().startsWith("score_9, score_8_up, score_7_up"));
        assertFalse(prompt.get().contains("source_anime"));
        assertTrue(prompt.get().contains("a black cat"));
        assertTrue(prompt.get().contains("old observatory"));
    }

    @Test
    void retriesWithLowMemoryProfileThenReturnsAVisibleNotice() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<ImageGenerationRequest> fallback = new AtomicReference<>();
        StoryIllustrationProvider provider = new StoryIllustrationProvider() {
            @Override
            public GeneratedImage generate(String prompt) {
                throw new ImageGenerationException("provider unavailable");
            }

            @Override
            public GeneratedImage generate(ImageGenerationRequest request) {
                if (attempts.incrementAndGet() == 2) {
                    fallback.set(request);
                }
                throw new ImageGenerationException("provider unavailable");
            }
        };
        StoryIllustrationService service = new StoryIllustrationService(
                tool(provider), true, 1000);

        var result = service.illustrate("แต่งนิทานให้หน่อย", "กาลครั้งหนึ่ง");

        assertEquals(2, attempts.get());
        assertTrue(result.attachments().isEmpty());
        assertTrue(result.notice().contains("สร้างภาพประกอบไม่สำเร็จ"));
        assertEquals(512, fallback.get().width());
        assertEquals(512, fallback.get().height());
        assertEquals(20, fallback.get().steps());
        assertFalse(service.shouldIllustrate("วันนี้อากาศเป็นอย่างไร"));
    }

    @Test
    void rendersStoryboardPanelsSequentiallyAsSeparateAttachments() {
        List<ImageGenerationRequest> requests = new ArrayList<>();
        StoryIllustrationProvider provider = new StoryIllustrationProvider() {
            @Override public GeneratedImage generate(String prompt) {
                throw new AssertionError("structured request expected");
            }

            @Override public GeneratedImage generate(ImageGenerationRequest request) {
                requests.add(request);
                return new GeneratedImage(GeneratedImageStoreTest.png(), "storyboard-model");
            }
        };
        StoryVisualPlanGenerator planner = (user, story, mode, memory, maximum) -> new StoryVisualPlan(
                mode, List.of(), List.of(
                        scene("Opening", "entering the observatory", "wide shot"),
                        scene("Turning point", "discovering a blue star", "medium shot"),
                        scene("Ending", "watching the dawn", "wide shot")));
        StoryIllustrationService service = new StoryIllustrationService(
                tool(provider), true, 4000, java.time.Duration.ZERO,
                planner, new InMemoryCharacterVisualMemory(), 3);

        var result = service.illustrate("owner", "story-1",
                "แต่งเรื่องพร้อมภาพแต่ละฉากแบบ storyboard", "เรื่องราวในหอดูดาว");

        assertEquals(3, result.attachments().size());
        assertEquals(3, requests.size());
        assertTrue(result.attachments().getFirst().title().contains("1/3"));
        assertTrue(result.attachments().getLast().title().contains("3/3"));
        assertTrue(requests.get(1).prompt().contains("discovering a blue star"));
        assertTrue(result.attachments().stream().allMatch(item -> item.seed() != null));
    }

    @Test
    void locksCharacterAppearanceAcrossTurnsInTheSameConversation() {
        List<ImageGenerationRequest> requests = new ArrayList<>();
        AtomicInteger plans = new AtomicInteger();
        StoryVisualPlanGenerator planner = (user, story, mode, memory, maximum) -> {
            String hair = plans.getAndIncrement() == 0 ? "long pink hair" : "short blue hair";
            CharacterVisualProfile profile = new CharacterVisualProfile(
                    "Mali", "1girl", List.of(hair), List.of("navy coat"), List.of(),
                    List.of("dark red eyes"), List.of("blonde hair"));
            return new StoryVisualPlan(mode, List.of(profile), List.of(new StorySceneSpec(
                    "Discovery", 1, List.of("Mali"), "looking through a telescope", "", List.of("telescope"),
                    "old observatory", "night", "", "hopeful", "quiet", "blue starlight", "navy",
                    "centered", "eye level", "medium shot", "sharp", List.of("blue star"), List.of(), List.of())));
        };
        StoryIllustrationProvider provider = new StoryIllustrationProvider() {
            @Override public GeneratedImage generate(String prompt) { throw new AssertionError(); }
            @Override public GeneratedImage generate(ImageGenerationRequest request) {
                requests.add(request);
                return new GeneratedImage(GeneratedImageStoreTest.png(), "memory-model");
            }
        };
        InMemoryCharacterVisualMemory memory = new InMemoryCharacterVisualMemory();
        StoryIllustrationService service = new StoryIllustrationService(
                tool(provider), true, 4000, java.time.Duration.ZERO, planner, memory, 3);

        service.illustrate("owner", "same-story", "แต่งเรื่องของมะลิตอนแรก", "มะลิพบดาว");
        service.illustrate("owner", "same-story", "แต่งเรื่องของมะลิตอนต่อไป", "มะลิกลับมาที่หอดูดาว");

        assertTrue(requests.getLast().prompt().contains("long pink hair"));
        assertFalse(requests.getLast().prompt().contains("short blue hair"));
        assertEquals("long pink hair", memory.find("owner", "same-story")
                .getFirst().appearance().getFirst());
    }

    private StorySceneSpec scene(String title, String action, String shot) {
        return new StorySceneSpec(title, 0, List.of(), action, "", List.of("telescope"),
                "old observatory", "night", "", "wonder", "cinematic", "blue starlight", "navy",
                "cinematic composition", "eye level", shot, "sharp", List.of("star"), List.of(), List.of());
    }

    private ImageGenerationTool tool(StoryIllustrationProvider provider) {
        return new ImageGenerationTool(
                provider,
                new GeneratedImageStore(directory, 1024, Clock.systemUTC()),
                8000,
                4_194_304L);
    }
}
