package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.task.TaskModelRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskModelStoryVisualPlanGeneratorTest {
    @Test
    void parsesAValidatedStructuredPlanAndIncludesLockedMemory() {
        List<TaskModelRequest> requests = new ArrayList<>();
        var generator = new TaskModelStoryVisualPlanGenerator(request -> {
            requests.add(request);
            return """
                    {"characters":[{"name":"Mali","identity":"1girl","appearance":["long pink hair"],
                    "clothing":["navy coat"],"accessories":["star pendant"],
                    "canonicalTags":["dark red eyes"],"negativeTags":["blonde hair"]}],
                    "scenes":[{"title":"Blue discovery","subjectCount":1,"characterNames":["Mali"],
                    "action":"looking through a brass telescope","interaction":"",
                    "keyObjects":["brass telescope"],"setting":"old observatory","time":"midnight",
                    "weather":"rain","emotion":"hope after grief","atmosphere":"quiet wonder",
                    "lighting":"blue starlight and warm lamp light","palette":"navy and cyan",
                    "composition":"balanced cinematic composition","cameraAngle":"slight low angle",
                    "shotDistance":"medium shot","focus":"shallow depth of field",
                    "mustInclude":["vivid blue star"],"mustNotInclude":["extra person"],
                    "fineDetails":["wet glass","star charts"]}]}
                    """;
        }, new ObjectMapper());
        CharacterVisualProfile remembered = new CharacterVisualProfile(
                "Mali", "1girl", List.of("long pink hair"), List.of(), List.of(),
                List.of("dark red eyes"), List.of("blonde hair"));

        StoryVisualPlan plan = generator.generate(
                "แต่งเรื่องหญิงสาวมะลิที่ค้นพบดาวสีฟ้าในหอดูดาว",
                "หญิงสาวมะลิค้นพบดาวสีฟ้าในหอดูดาวเก่า",
                StoryIllustrationMode.DECISIVE_SCENE, List.of(remembered), 3);

        assertEquals(1, plan.scenes().size());
        assertEquals("old observatory", plan.scenes().getFirst().setting());
        assertEquals("long pink hair", plan.characters().getFirst().appearance().getFirst());
        assertEquals(TaskModelRequest.ResponseFormat.JSON_OBJECT, requests.getFirst().responseFormat());
        assertTrue(requests.getFirst().messages().getLast().content().contains("LOCKED CHARACTER VISUAL MEMORY"));
        assertTrue(requests.getFirst().messages().getLast().content().contains("long pink hair"));
    }

    @Test
    void rejectsInventedPeopleAndFallsBackToSafeStoryAnchors() {
        var generator = new TaskModelStoryVisualPlanGenerator(request -> """
                {"characters":[{"name":"Alice","identity":"1girl","appearance":["blonde hair"],
                "clothing":[],"accessories":[],"canonicalTags":[],"negativeTags":[]}],
                "scenes":[{"title":"Wrong","subjectCount":1,"characterNames":["Alice"],
                "action":"reading","interaction":"","keyObjects":[],"setting":"library","time":"day",
                "weather":"","emotion":"happy","atmosphere":"calm","lighting":"sunlight","palette":"warm",
                "composition":"centered","cameraAngle":"eye level","shotDistance":"medium shot","focus":"sharp",
                "mustInclude":[],"mustNotInclude":[],"fineDetails":[]}]}
                """, new ObjectMapper());

        StoryVisualPlan plan = generator.generate(
                "แต่งเรื่องแมวดำในหอดูดาว", "แมวดำเฝ้ามองแสงดาวในหอดูดาว",
                StoryIllustrationMode.DECISIVE_SCENE, List.of(), 3);
        PonyStoryPromptCompiler.CompiledPrompt prompt = new PonyStoryPromptCompiler().compile(
                plan.mode(), plan.scenes().getFirst(), plan.characters());

        assertTrue(prompt.positive().contains("black cat"));
        assertTrue(prompt.positive().contains("old observatory"));
        assertTrue(prompt.positive().contains("glowing starlight"));
        assertFalse(prompt.positive().contains("Alice"));
    }

    @Test
    void removesForbiddenSourceTagsAtTheSchemaBoundary() {
        CharacterVisualProfile profile = new CharacterVisualProfile(
                "Mali", "1girl", List.of("source_anime", "pink hair"), List.of(), List.of(),
                List.of("source_anime"), List.of());
        StorySceneSpec scene = new StorySceneSpec(
                "Portrait", 1, List.of("Mali"), "standing", "", List.of(), "observatory", "night", "",
                "calm", "quiet", "moonlight", "blue", "centered", "eye level", "portrait", "sharp",
                List.of("source_anime"), List.of(), List.of());

        String prompt = new PonyStoryPromptCompiler().compile(
                StoryIllustrationMode.CHARACTER_PORTRAIT, scene, List.of(profile)).positive();

        assertFalse(prompt.contains("source_anime"));
        assertTrue(prompt.contains("pink hair"));
    }
}
