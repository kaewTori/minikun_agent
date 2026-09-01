package com.minikun.visual;

import java.util.List;

/** Structured output consumed by the Pony compiler and sequential renderer. */
public record StoryVisualPlan(
        StoryIllustrationMode mode,
        List<CharacterVisualProfile> characters,
        List<StorySceneSpec> scenes) {
    public StoryVisualPlan {
        if (mode == null) throw new IllegalArgumentException("illustration mode is required");
        characters = characters == null ? List.of() : List.copyOf(characters);
        scenes = scenes == null ? List.of() : List.copyOf(scenes);
        int maximum = mode == StoryIllustrationMode.STORYBOARD ? 5 : 1;
        if (scenes.isEmpty() || scenes.size() > maximum) {
            throw new IllegalArgumentException("visual plan contains an invalid scene count");
        }
    }
}
