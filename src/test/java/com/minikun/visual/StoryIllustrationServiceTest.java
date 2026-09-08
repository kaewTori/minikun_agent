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
        StoryVisualPlanGenerator planner = (user, story, mode, memory, maximum) ->
                new StoryVisualPlan(mode, List.of(), List.of(
                        scene("White cat", "a white cat sleeping on a red sofa", "medium wide shot")));
        StoryIllustrationService service = new StoryIllustrationService(
                tool(provider), true, 2000,
                planner, new InMemoryCharacterVisualMemory(), 3);

        var result = service.illustrate(
                "วาดรูปแมวสีขาวนอนบนโซฟาสีแดง",
                "แมวสีขาวนอนบนโซฟาสีแดงใต้แสงดาวบนหลังคาหอดูดาว");
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
        assertTrue(prompt.get().contains("a white cat"));
        assertTrue(prompt.get().contains("red sofa"));
        assertFalse(prompt.get().contains("a black cat"));
        assertTrue(prompt.get().contains("old observatory"));
    }

    @Test
    void doesNotRetryFailedImageGeneration() {
        AtomicInteger attempts = new AtomicInteger();
        StoryIllustrationProvider provider = new StoryIllustrationProvider() {
            @Override
            public GeneratedImage generate(String prompt) {
                throw new ImageGenerationException("provider unavailable");
            }

            @Override
            public GeneratedImage generate(ImageGenerationRequest request) {
                attempts.incrementAndGet();
                throw new ImageGenerationException("provider unavailable");
            }
        };
        StoryIllustrationService service = new StoryIllustrationService(
                tool(provider), true, 1000,
                (user, story, mode, memory, maximum) -> new StoryVisualPlan(
                        mode, List.of(), List.of(scene("Opening", "opening the door", "wide shot"))),
                new InMemoryCharacterVisualMemory(), 3);

        var result = service.illustrate("แต่งนิทานให้หน่อย", "กาลครั้งหนึ่ง");

        assertEquals(1, attempts.get());
        assertTrue(result.attachments().isEmpty());
        assertTrue(result.notice().contains("สร้างภาพประกอบไม่สำเร็จ"));
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
                tool(provider), true, 4000,
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
                tool(provider), true, 4000, planner, memory, 3);

        service.illustrate("owner", "same-story", "แต่งเรื่องของมะลิตอนแรก", "มะลิพบดาว");
        service.illustrate("owner", "same-story", "แต่งเรื่องของมะลิตอนต่อไป", "มะลิกลับมาที่หอดูดาว");

        assertTrue(requests.getLast().prompt().contains("long pink hair"));
        assertFalse(requests.getLast().prompt().contains("short blue hair"));
        assertEquals("long pink hair", memory.find("owner", "same-story")
                .getFirst().appearance().getFirst());
    }

    @Test
    void plannerAndTransformerFailureUseDeterministicTinyGradFallback() {
        AtomicInteger providerCalls = new AtomicInteger();
        StoryIllustrationProvider provider = new StoryIllustrationProvider() {
            @Override public GeneratedImage generate(String prompt) { throw new AssertionError(); }
            @Override public GeneratedImage generate(ImageGenerationRequest request) {
                providerCalls.incrementAndGet();
                return new GeneratedImage(GeneratedImageStoreTest.png(), "pony-model");
            }
        };
        StoryIllustrationService service = new StoryIllustrationService(
                tool(provider), true, 4000,
                (user, story, mode, memory, maximum) -> null,
                new InMemoryCharacterVisualMemory(), 3);

        var result = service.illustrate("owner", "story-failure",
                "เล่าเรื่อง Itsuki กับ Rena", "Itsuki และ Rena เผชิญหน้ากันในห้องสวีทของโรงแรม");

        assertEquals(1, providerCalls.get());
        assertEquals(1, result.attachments().size());
        assertTrue(result.notice().isEmpty());
    }

    @Test
    void plannerFailureFallsBackToExistingPromptTransformerThenTinyGradProvider() {
        AtomicReference<ImageGenerationRequest> request = new AtomicReference<>();
        StoryIllustrationProvider provider = new StoryIllustrationProvider() {
            @Override public GeneratedImage generate(String prompt) { throw new AssertionError(); }
            @Override public GeneratedImage generate(ImageGenerationRequest value) {
                request.set(value);
                return new GeneratedImage(GeneratedImageStoreTest.png(), "tinygrad-pony");
            }
        };
        PonyPromptTransformer transformer = brief -> {
            assertTrue(brief.contains("Itsuki"));
            return "score_9, score_8_up, score_7_up, 2girls, hotel suite, tense rivalry";
        };
        StoryIllustrationService service = new StoryIllustrationService(
                tool(provider), true, 4000,
                (user, story, mode, memory, maximum) -> { throw new IllegalArgumentException("empty scene"); },
                new InMemoryCharacterVisualMemory(), 3, transformer);

        var result = service.illustrate("owner", "story-fallback",
                "เล่าเรื่อง Itsuki กับ Rena", "Itsuki และ Rena เผชิญหน้ากันในโรงแรม");

        assertEquals(1, result.attachments().size());
        assertTrue(result.notice().isEmpty());
        assertEquals("tinygrad-pony", result.attachments().getFirst().provider());
        assertTrue(request.get().prompt().contains("2girls, hotel suite, tense rivalry"));
    }

    @Test
    void rejectsFallbackTransformerGenderHallucinationBeforeTinyGrad() {
        AtomicReference<ImageGenerationRequest> request = new AtomicReference<>();
        StoryIllustrationProvider provider = new StoryIllustrationProvider() {
            @Override public GeneratedImage generate(String prompt) { throw new AssertionError(); }
            @Override public GeneratedImage generate(ImageGenerationRequest value) {
                request.set(value);
                return new GeneratedImage(GeneratedImageStoreTest.png(), "tinygrad-pony");
            }
        };
        StoryIllustrationService service = new StoryIllustrationService(
                tool(provider), true, 4000,
                (user, story, mode, memory, maximum) -> { throw new IllegalArgumentException("bad plan"); },
                new InMemoryCharacterVisualMemory(), 3,
                brief -> "score_9, score_8_up, score_7_up, 1boy, observatory, starlight");

        var result = service.illustrate("owner", "story-fallback",
                "แต่งเรื่องหญิงสาวมะลิ", "หญิงสาวมะลิมองดาวในหอดูดาว");

        assertEquals(1, result.attachments().size());
        assertTrue(request.get().prompt().contains("1girl"));
        assertTrue(request.get().prompt().contains("observatory"));
        assertFalse(request.get().prompt().contains("1boy"));
    }

    @Test
    void anchorsExplicitFemaleStoryWhenFallbackPromptOmitsGender() {
        AtomicReference<ImageGenerationRequest> request = new AtomicReference<>();
        StoryIllustrationProvider provider = new StoryIllustrationProvider() {
            @Override public GeneratedImage generate(String prompt) { throw new AssertionError(); }
            @Override public GeneratedImage generate(ImageGenerationRequest value) {
                request.set(value);
                return new GeneratedImage(GeneratedImageStoreTest.png(), "tinygrad-pony");
            }
        };
        StoryIllustrationService service = new StoryIllustrationService(
                tool(provider), true, 4000,
                (user, story, mode, memory, maximum) -> { throw new IllegalArgumentException("bad plan"); },
                new InMemoryCharacterVisualMemory(), 3,
                brief -> "score_9, score_8_up, score_7_up, observatory, starlight");

        service.illustrate("owner", "story-fallback",
                "แต่งเรื่องหญิงสาวมะลิ", "หญิงสาวมะลิมองดาวในหอดูดาว");

        assertTrue(request.get().prompt().contains("1girl"));
        assertFalse(request.get().prompt().contains("1boy"));
    }

    @Test
    void directStoryboardUsesDistinctTransformerBriefsWithoutLossyStoryPlanning() {
        List<ImageGenerationRequest> requests = new ArrayList<>();
        List<String> briefs = new ArrayList<>();
        StoryIllustrationProvider provider = new StoryIllustrationProvider() {
            @Override public GeneratedImage generate(String prompt) { throw new AssertionError(); }
            @Override public GeneratedImage generate(ImageGenerationRequest value) {
                requests.add(value);
                return new GeneratedImage(GeneratedImageStoreTest.png(), "tinygrad-pony");
            }
        };
        PonyPromptTransformer transformer = brief -> {
            briefs.add(brief);
            assertTrue(brief.contains("นกฮูกสีขาว"));
            assertTrue(brief.contains("ภาพอ้างอิงมีนกฮูกเกาะบนกิ่งไม้"));
            return "score_9, score_8_up, score_7_up, white owl, purple necklace, "
                    + "wooden sign, red windmill, lavender field, night, no humans";
        };
        StoryIllustrationService service = new StoryIllustrationService(
                tool(provider), true, 4000,
                (user, story, mode, memory, maximum) -> { throw new AssertionError("planner must not run"); },
                new InMemoryCharacterVisualMemory(), 3, transformer);

        var result = service.illustrate("owner", "direct-image",
                "ช่วยวาดภาพ storyboard นกฮูกสีขาว สร้อยสีม่วง ป้ายไม้ กังหันแดง ทุ่งลาเวนเดอร์กลางคืน ห้ามมีมนุษย์",
                "ภาพอ้างอิงมีนกฮูกเกาะบนกิ่งไม้ มินิคุงจะออกแบบฉากใหม่ให้ต่างจากต้นฉบับ");

        assertEquals(3, result.attachments().size());
        assertEquals(3, briefs.stream().distinct().count());
        assertTrue(briefs.stream().allMatch(brief -> brief.contains("STORY FACTS (apply to every panel)")));
        assertTrue(requests.stream().allMatch(request -> request.prompt().contains("white owl")));
        assertTrue(requests.stream().allMatch(request -> request.prompt().contains("purple necklace")));
        assertTrue(requests.stream().allMatch(request -> request.prompt().contains("red windmill")));
        assertTrue(requests.stream().allMatch(request -> request.negativePrompt().contains("human")));
    }

    @Test
    void plannerFailureKeepsAllStoryboardPanels() {
        List<ImageGenerationRequest> requests = new ArrayList<>();
        List<String> briefs = new ArrayList<>();
        StoryIllustrationProvider provider = new StoryIllustrationProvider() {
            @Override public GeneratedImage generate(String prompt) { throw new AssertionError(); }
            @Override public GeneratedImage generate(ImageGenerationRequest request) {
                requests.add(request);
                return new GeneratedImage(GeneratedImageStoreTest.png(), "tinygrad-pony");
            }
        };
        StoryIllustrationService service = new StoryIllustrationService(
                tool(provider), true, 4000,
                (user, story, mode, memory, maximum) -> { throw new IllegalArgumentException("empty scene"); },
                new InMemoryCharacterVisualMemory(), 3,
                brief -> {
                    briefs.add(brief);
                    return "score_9, score_8_up, score_7_up, observatory, blue star";
                });

        var result = service.illustrate("owner", "storyboard-fallback",
                "แต่งเรื่องพร้อมภาพแต่ละฉากแบบ storyboard", "A blue star appears in an observatory.");

        assertEquals(3, result.attachments().size());
        assertEquals(3, requests.size());
        assertEquals(3, briefs.stream().distinct().count());
        assertTrue(briefs.getFirst().contains("opening story moment"));
        assertTrue(briefs.getLast().contains("emotional ending moment"));
        assertTrue(result.attachments().getFirst().title().contains("1/3"));
        assertTrue(result.attachments().getLast().title().contains("3/3"));
        assertTrue(requests.getFirst().prompt().contains("opening story moment"));
        assertTrue(requests.getLast().prompt().contains("emotional ending moment"));
    }

    @Test
    void sendsTheCompiledPlanWithOneQualityPrefixAndNoDuplicateComposition() {
        AtomicReference<ImageGenerationRequest> request = new AtomicReference<>();
        StoryIllustrationProvider provider = new StoryIllustrationProvider() {
            @Override public GeneratedImage generate(String prompt) { throw new AssertionError(); }
            @Override public GeneratedImage generate(ImageGenerationRequest value) {
                request.set(value);
                return new GeneratedImage(GeneratedImageStoreTest.png(), "pony-model");
            }
        };
        StoryVisualPlanGenerator planner = (user, story, mode, memory, maximum) ->
                new StoryVisualPlan(mode, List.of(), List.of(
                        scene("Discovery", "discovering a blue star", "medium shot")));
        StoryIllustrationService service = new StoryIllustrationService(
                tool(provider), true, 4000,
                planner, new InMemoryCharacterVisualMemory(), 3);

        service.illustrate("ช่วยวาดภาพฉากสำคัญ", "นักสำรวจค้นพบดาวสีฟ้าในหอดูดาว");

        assertEquals(1, request.get().prompt().split("score_9", -1).length - 1);
        assertEquals(1, request.get().prompt().split("cinematic composition", -1).length - 1);
        assertTrue(request.get().prompt().contains("discovering a blue star"));
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
