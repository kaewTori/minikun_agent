package com.minikun.visual;

import java.util.List;

public interface StoryVisualPlanGenerator {
    StoryVisualPlan generate(
            String userMessage,
            String assistantStory,
            StoryIllustrationMode mode,
            List<CharacterVisualProfile> rememberedCharacters,
            int maximumStoryboardScenes);
}
