package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.model.task.TaskModelRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TaskModelStoryVisualPromptGeneratorTest {
    @Test
    void extractsTheStoryEssenceBeforeCreatingDetailedPonyTags() {
        List<TaskModelRequest> requests = new ArrayList<>();
        AtomicInteger call = new AtomicInteger();
        var generator = new TaskModelStoryVisualPromptGenerator(value -> {
            requests.add(value);
            if (call.getAndIncrement() == 0) {
                return "A black cat discovers glowing starlight inside an old observatory at night, quiet wonder, "
                        + "blue moonlight, wide cinematic composition.";
            }
            return "Prompt: source_anime, black cat, solo animal, fluffy black fur, old observatory, telescope, "
                    + "glowing starlight, night, quiet wonder, blue moonlight, silver highlights, wide shot, "
                    + "low angle, cinematic composition, detailed background, starry sky, depth of field";
        });

        String result = generator.generate("แต่งเรื่องเกี่ยวกับแมว", "แมวดำพบแสงดาวในหอดูดาว");

        assertTrue(result.startsWith("black cat, solo animal"));
        assertFalse(result.contains("source_anime"));
        assertEquals(2, requests.size());
        assertEquals(220, requests.getFirst().maxOutputTokens());
        assertTrue(requests.getFirst().messages().getFirst().content().contains("essential meaning"));
        assertEquals(240, requests.getLast().maxOutputTokens());
        assertTrue(requests.getLast().messages().getFirst().content().contains("55 to 90"));
        assertTrue(requests.getLast().messages().getFirst().content().contains("SCENE ESSENCE"));
    }

    @Test
    void fallsBackToEnglishKeywordsWhenTaskModelFails() {
        var generator = new TaskModelStoryVisualPromptGenerator(value -> {
            throw new IllegalStateException("offline");
        });

        String result = generator.generate("เรื่องแมวในหอดูดาว", "มันมองเห็นดาว");

        assertTrue(result.contains("black cat"));
        assertTrue(result.contains("old observatory"));
        assertTrue(result.contains("starlight"));
    }

    @Test
    void usesAnEmbeddedVisualBriefAsTheStoryEssence() {
        List<TaskModelRequest> requests = new ArrayList<>();
        var generator = new TaskModelStoryVisualPromptGenerator(value -> {
            requests.add(value);
            return "1girl, solo, female astronomer, black hair, tired eyes, telescope, old observatory, "
                    + "blue star, rain, night, grief, renewed hope, warm indoor light, cold blue light, "
                    + "cinematic composition, medium shot, detailed background";
        });

        String result = generator.generate(
                "เรื่องนักดาราศาสตร์หญิงในหอดูดาวที่ค้นพบดาวสีฟ้า",
                "เรื่องยาวภาษาไทย...\n**ภาพประกอบ (Visual Brief):**\n"
                        + "A black-haired female astronomer discovers a blue star in an old observatory during rain.");

        assertTrue(result.contains("female astronomer"));
        assertEquals(1, requests.size());
        assertTrue(requests.getFirst().messages().getFirst().content().contains("black-haired female astronomer"));
    }

    @Test
    void rejectsFluentPonyTagsThatDropTheDefiningStoryScene() {
        AtomicInteger call = new AtomicInteger();
        var generator = new TaskModelStoryVisualPromptGenerator(value -> call.getAndIncrement() == 0
                ? "A black cat sees starlight inside an old observatory."
                : "1girl, solo, blonde hair, school uniform, reading book, library, daylight, close-up, "
                        + "blue eyes, gentle smile, upper body, window light, detailed background, depth of field");

        String result = generator.generate("เรื่องแมวในหอดูดาว", "แมวดำมองเห็นแสงดาว");

        assertTrue(result.contains("black cat"));
        assertTrue(result.contains("old observatory"));
        assertTrue(result.contains("starlight"));
    }

    @Test
    void stripsFieldLabelsAndStopsBeforeUnrelatedQuestionAnswerContamination() {
        var generator = new TaskModelStoryVisualPromptGenerator(value ->
                "subject count: 1, identity: female astronomer, appearance: black hair, tired blue eyes, "
                        + "clothing: navy sweater, pose: seated, decisive action: discovering a blue star, "
                        + "key objects: brass telescope, research notebook, exact setting: old observatory, "
                        + "time: midnight, emotion: grief turning into hope, atmosphere: quiet wonder, "
                        + "lighting: warm lamp, cold blue starlight, palette: navy and cyan, "
                        + "composition: centered composition, camera angle: slight low angle, "
                        + "shot distance: medium shot, focus: shallow depth of field, "
                        + "fine visible details: wet glass, star charts, Q: how to get a variable from a function?");

        String result = generator.generate(
                "นักดาราศาสตร์หญิงค้นพบดาวสีฟ้าในหอดูดาว",
                "**Visual Brief:** A black-haired female astronomer discovers a blue star in an old observatory.");

        assertTrue(result.startsWith("1, female astronomer, black hair"));
        assertFalse(result.contains("subject count"));
        assertFalse(result.toLowerCase().contains("how to"));
        assertFalse(result.toLowerCase().contains("function"));
    }
}
