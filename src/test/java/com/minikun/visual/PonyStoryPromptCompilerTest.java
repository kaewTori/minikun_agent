package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PonyStoryPromptCompilerTest {
    @Test
    void followsThePonyGuideOrderAndKeepsStoryFacts() {
        CharacterVisualProfile left = new CharacterVisualProfile(
                "Mali", "1girl", List.of("long pink hair", "red eyes"),
                List.of("navy coat"), List.of("star pendant"), List.of(), List.of());
        CharacterVisualProfile right = new CharacterVisualProfile(
                "Rin", "1girl", List.of("short black hair"),
                List.of("black jacket"), List.of(), List.of(), List.of());
        StorySceneSpec scene = new StorySceneSpec(
                "Discovery", 2, List.of("Mali", "Rin"),
                "Mali points at a blue star", "Rin holds Mali's hand",
                List.of("brass telescope"), "old observatory", "midnight", "rain",
                "wonder", "quiet", "blue starlight", "navy and cyan",
                "balanced composition", "low angle", "medium shot", "sharp",
                List.of("vivid blue star"), List.of(), List.of("wet glass"));

        String prompt = new PonyStoryPromptCompiler()
                .compile(StoryIllustrationMode.DECISIVE_SCENE, scene, List.of(left, right))
                .positive();

        assertTrue(prompt.startsWith("score_9, score_8_up, score_7_up, score_6_up, rating_safe"));
        assertTrue(prompt.indexOf("A detailed story moment") > prompt.indexOf("rating_safe"));
        assertTrue(prompt.indexOf("Style:") > prompt.indexOf("A detailed story moment"));
        assertTrue(prompt.contains("left girl with long pink hair"));
        assertTrue(prompt.contains("right girl with short black hair"));
        assertTrue(prompt.contains("blue star"));
        assertTrue(prompt.contains("2girls"));
    }

    @Test
    void carriesTheOnlyKnownCharacterIntoAnUnnamedPanel() {
        CharacterVisualProfile girl = new CharacterVisualProfile(
                "Mali", "1girl", List.of("long black hair"), List.of(), List.of(), List.of(), List.of());
        StorySceneSpec scene = new StorySceneSpec(
                "Later panel", 1, List.of(), "looking at the stars", "", List.of("telescope"),
                "old observatory", "night", "", "calm", "quiet", "starlight", "navy",
                "centered", "eye level", "medium shot", "sharp", List.of(), List.of(), List.of());

        PonyStoryPromptCompiler.CompiledPrompt prompt = new PonyStoryPromptCompiler().compile(
                StoryIllustrationMode.STORYBOARD, scene, List.of(girl));

        assertTrue(prompt.positive().contains("1girl"));
        assertTrue(prompt.negative().contains("1boy"));
    }
}
