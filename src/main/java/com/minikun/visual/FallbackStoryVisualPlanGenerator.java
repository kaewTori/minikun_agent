package com.minikun.visual;

import java.util.ArrayList;
import java.util.List;

/** Deterministic local fallback used when no task model is configured. */
public final class FallbackStoryVisualPlanGenerator implements StoryVisualPlanGenerator {
    @Override
    public StoryVisualPlan generate(String userMessage, String assistantStory, StoryIllustrationMode mode,
            List<CharacterVisualProfile> rememberedCharacters, int maximumStoryboardScenes) {
        String prompt = StoryVisualPromptGenerator.fallback(userMessage, assistantStory);
        List<StorySceneSpec> scenes = new ArrayList<>();
        if (mode == StoryIllustrationMode.STORYBOARD) {
            scenes.add(StorySceneSpec.fallback(prompt, "Opening", "opening story moment",
                    "establishing composition", "wide shot"));
            if (maximumStoryboardScenes > 2) {
                scenes.add(StorySceneSpec.fallback(prompt, "Turning point", "decisive turning point",
                        "dynamic composition", "medium shot"));
            }
            scenes.add(StorySceneSpec.fallback(prompt, "Ending", "emotional ending moment",
                    "resolved cinematic composition", "wide shot"));
        } else {
            scenes.add(StorySceneSpec.fallback(prompt, mode.name(), switch (mode) {
                case COVER -> "symbolic story summary";
                case CHARACTER_PORTRAIT -> "character identity study";
                case ENDING_SCENE -> "the story's final visible moment";
                default -> "the story's decisive emotional moment";
            }, mode == StoryIllustrationMode.CHARACTER_PORTRAIT
                    ? "centered portrait composition" : "cinematic composition",
                    mode == StoryIllustrationMode.CHARACTER_PORTRAIT ? "portrait shot" : "medium wide shot"));
        }
        int limit = mode == StoryIllustrationMode.STORYBOARD
                ? Math.max(2, Math.min(5, maximumStoryboardScenes)) : 1;
        return new StoryVisualPlan(mode,
                rememberedCharacters == null ? List.of() : rememberedCharacters,
                scenes.stream().limit(limit).toList());
    }
}
