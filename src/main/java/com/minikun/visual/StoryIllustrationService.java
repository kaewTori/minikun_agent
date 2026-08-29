package com.minikun.visual;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/** Fail-open post-processor that turns a story's defining moment into an attachment. */
@Slf4j
public final class StoryIllustrationService {
    private final StoryIllustrationProvider provider;
    private final GeneratedImageStore store;
    private final StoryIllustrationIntentDetector intentDetector;
    private final boolean autoIllustrateCreativeStories;
    private final int maximumPromptCharacters;

    public StoryIllustrationService(
            StoryIllustrationProvider provider,
            GeneratedImageStore store,
            boolean autoIllustrateCreativeStories,
            int maximumPromptCharacters) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.intentDetector = new StoryIllustrationIntentDetector();
        this.autoIllustrateCreativeStories = autoIllustrateCreativeStories;
        if (maximumPromptCharacters < 500) {
            throw new IllegalArgumentException("maximum illustration prompt must be at least 500 characters");
        }
        this.maximumPromptCharacters = maximumPromptCharacters;
    }

    public boolean shouldIllustrate(String userMessage) {
        return intentDetector.detect(userMessage, autoIllustrateCreativeStories)
                != StoryIllustrationIntent.NONE;
    }

    public List<ChatAttachment> illustrate(String userMessage, String assistantStory) {
        StoryIllustrationIntent intent = intentDetector.detect(userMessage, autoIllustrateCreativeStories);
        if (intent == StoryIllustrationIntent.NONE) {
            return List.of();
        }
        try {
            GeneratedImage generated = provider.generate(prompt(userMessage, assistantStory));
            GeneratedImageStore.StoredImage stored = store.save(generated.bytes());
            String description = generated.revisedPrompt().isBlank()
                    ? "ภาพที่สร้างขึ้นเพื่อประกอบเรื่องราวในคำตอบนี้"
                    : clip(generated.revisedPrompt(), 600);
            return List.of(new ChatAttachment(
                    "image",
                    stored.url(),
                    title(intent),
                    "",
                    description,
                    "generated",
                    stored.url(),
                    "",
                    null,
                    null,
                    generated.provider(),
                    ""));
        } catch (RuntimeException exception) {
            log.warn("process=story_illustration event=failed reason={}", exception.getMessage());
            return List.of();
        }
    }

    private String prompt(String userMessage, String assistantStory) {
        String request = clip(userMessage, Math.min(1_500, maximumPromptCharacters / 3));
        String story = clip(assistantStory, maximumPromptCharacters - request.length() - 500);
        return clip("""
                Create one polished, standalone illustration for the story below. Depict the single most emotionally
                decisive visual moment, with clear subject, setting, action, lighting, atmosphere, and composition.
                Preserve character identity and story-specific details. Use a cinematic storybook aesthetic unless
                the request names another style. Do not make a collage. Do not add captions, speech bubbles,
                watermarks, logos, or readable text. Do not depict details that contradict the story.

                Original request:
                %s

                Story or visual brief:
                %s
                """.formatted(request, story).strip(), maximumPromptCharacters);
    }

    private String title(StoryIllustrationIntent intent) {
        return intent == StoryIllustrationIntent.DIRECT_IMAGE
                ? "ภาพที่มินิคุงสร้างให้"
                : "ภาพประกอบเรื่องราวโดยมินิคุง";
    }

    private String clip(String value, int maximum) {
        String result = value == null ? "" : value.strip();
        if (result.length() <= maximum) {
            return result;
        }
        return result.substring(0, Math.max(0, maximum - 1)).stripTrailing() + "…";
    }
}
