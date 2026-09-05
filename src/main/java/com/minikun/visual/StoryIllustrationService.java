package com.minikun.visual;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/** Fail-open post-processor that turns a story's defining moment into an attachment. */
@Slf4j
public final class StoryIllustrationService {
    static final String FAILURE_NOTICE = "\n\n> ⚠️ มินิคุงสร้างภาพประกอบไม่สำเร็จ"
            + "หลังลองโหมดประหยัดหน่วยความจำแล้ว กรุณาลองอีกครั้งเมื่อ TinyGrad พร้อมใช้งานครับ";

    private final ImageGenerationTool imageGenerationTool;
    private final StoryIllustrationIntentDetector intentDetector;
    private final boolean autoIllustrateCreativeStories;
    private final int maximumPromptCharacters;
    private final Duration recoveryTimeout;
    private final StoryVisualPlanGenerator visualPlanGenerator;
    private final CharacterVisualMemory characterMemory;
    private final PonyStoryPromptCompiler promptCompiler;
    private final StoryIllustrationModeDetector modeDetector;
    private final int maximumStoryboardScenes;
    private final PonyPromptTransformer fallbackPromptTransformer;

    public StoryIllustrationService(
            ImageGenerationTool imageGenerationTool,
            boolean autoIllustrateCreativeStories,
            int maximumPromptCharacters) {
        this(imageGenerationTool, autoIllustrateCreativeStories, maximumPromptCharacters, Duration.ZERO,
                StoryVisualPromptGenerator::fallback);
    }

    public StoryIllustrationService(
            ImageGenerationTool imageGenerationTool,
            boolean autoIllustrateCreativeStories,
            int maximumPromptCharacters,
            Duration recoveryTimeout) {
        this(imageGenerationTool, autoIllustrateCreativeStories, maximumPromptCharacters, recoveryTimeout,
                StoryVisualPromptGenerator::fallback);
    }

    public StoryIllustrationService(
            ImageGenerationTool imageGenerationTool,
            boolean autoIllustrateCreativeStories,
            int maximumPromptCharacters,
            Duration recoveryTimeout,
            StoryVisualPromptGenerator visualPromptGenerator) {
        this(imageGenerationTool, autoIllustrateCreativeStories, maximumPromptCharacters, recoveryTimeout,
                legacyPlanGenerator(visualPromptGenerator), new InMemoryCharacterVisualMemory(), 3);
    }

    public StoryIllustrationService(
            ImageGenerationTool imageGenerationTool,
            boolean autoIllustrateCreativeStories,
            int maximumPromptCharacters,
            Duration recoveryTimeout,
            StoryVisualPlanGenerator visualPlanGenerator,
            CharacterVisualMemory characterMemory,
            int maximumStoryboardScenes) {
        this(imageGenerationTool, autoIllustrateCreativeStories, maximumPromptCharacters, recoveryTimeout,
                visualPlanGenerator, characterMemory, maximumStoryboardScenes, null);
    }

    public StoryIllustrationService(
            ImageGenerationTool imageGenerationTool,
            boolean autoIllustrateCreativeStories,
            int maximumPromptCharacters,
            Duration recoveryTimeout,
            StoryVisualPlanGenerator visualPlanGenerator,
            CharacterVisualMemory characterMemory,
            int maximumStoryboardScenes,
            PonyPromptTransformer fallbackPromptTransformer) {
        this.imageGenerationTool = Objects.requireNonNull(
                imageGenerationTool, "image generation tool must not be null");
        this.intentDetector = new StoryIllustrationIntentDetector();
        this.autoIllustrateCreativeStories = autoIllustrateCreativeStories;
        if (maximumPromptCharacters < 500) {
            throw new IllegalArgumentException("maximum illustration prompt must be at least 500 characters");
        }
        this.maximumPromptCharacters = maximumPromptCharacters;
        this.recoveryTimeout = recoveryTimeout == null || recoveryTimeout.isNegative()
                ? Duration.ZERO : recoveryTimeout;
        this.visualPlanGenerator = Objects.requireNonNull(
                visualPlanGenerator, "visual plan generator must not be null");
        this.characterMemory = Objects.requireNonNull(characterMemory, "character memory must not be null");
        if (maximumStoryboardScenes < 2 || maximumStoryboardScenes > 5) {
            throw new IllegalArgumentException("maximum storyboard scenes must be between 2 and 5");
        }
        this.maximumStoryboardScenes = maximumStoryboardScenes;
        this.fallbackPromptTransformer = fallbackPromptTransformer;
        this.promptCompiler = new PonyStoryPromptCompiler();
        this.modeDetector = new StoryIllustrationModeDetector();
    }

    public boolean shouldIllustrate(String userMessage) {
        return intentDetector.detect(userMessage, autoIllustrateCreativeStories)
                != StoryIllustrationIntent.NONE;
    }

    public IllustrationResult illustrate(String userMessage, String assistantStory) {
        return illustrate("default", "standalone", userMessage, assistantStory);
    }

    public IllustrationResult illustrate(
            String ownerId,
            String conversationId,
            String userMessage,
            String assistantStory) {
        StoryIllustrationIntent intent = intentDetector.detect(userMessage, autoIllustrateCreativeStories);
        if (intent == StoryIllustrationIntent.NONE) {
            return IllustrationResult.empty();
        }
        StoryIllustrationMode mode = modeDetector.detect(userMessage);
        List<CharacterVisualProfile> remembered = readMemory(ownerId, conversationId);
        StoryVisualPlan plan = visualPlanGenerator.generate(
                userMessage, assistantStory, mode, remembered, maximumStoryboardScenes);
        List<CharacterVisualProfile> lockedCharacters = lockCharacters(remembered, plan.characters());
        saveMemory(ownerId, conversationId, lockedCharacters);

        List<ChatAttachment> attachments = new ArrayList<>();
        for (int index = 0; index < plan.scenes().size(); index++) {
            StorySceneSpec scene = plan.scenes().get(index);
            PonyStoryPromptCompiler.CompiledPrompt compiled = promptCompiler.compile(
                    mode, scene, lockedCharacters);
            String attachmentTitle = title(intent, mode, scene, index, plan.scenes().size());
            ImageGenerationTool.Generation generated = generateScene(
                    compiled, ownerId, conversationId, mode, attachmentTitle, userMessage, assistantStory);
            if (generated == null) {
                return attachments.isEmpty()
                        ? IllustrationResult.failure(FAILURE_NOTICE)
                        : new IllustrationResult(attachments, FAILURE_NOTICE);
            }
            attachments.add(attachment(attachmentTitle, generated));
        }
        return IllustrationResult.success(attachments);
    }

    private ChatAttachment attachment(
            String title,
            ImageGenerationTool.Generation generated) {
        return new ChatAttachment(
                "image",
                generated.url(),
                title,
                "",
                "ภาพที่ TinyGrad สร้างขึ้นเพื่อประกอบเรื่องราวในคำตอบนี้",
                "generated",
                generated.url(),
                "",
                null,
                null,
                generated.provider(),
                "",
                generated.prompt(),
                generated.negativePrompt(),
                generated.seed(),
                generated.historyId().toString());
    }

    private ImageGenerationTool.Generation generateScene(
            PonyStoryPromptCompiler.CompiledPrompt compiled,
            String ownerId,
            String conversationId,
            StoryIllustrationMode mode,
            String sceneTitle,
            String userMessage,
            String assistantStory) {
        PreparedPrompt prepared = prompt(compiled, userMessage, assistantStory);
        ImageGenerationScope scope = new ImageGenerationScope(
                ownerId, conversationId, "generated", mode.name().toLowerCase(java.util.Locale.ROOT), sceneTitle);
        ImageGenerationRequest request = new ImageGenerationRequest(
                prepared.prompt(), clip(compiled.negative(), maximumPromptCharacters), prepared.facePrompts(),
                null, null, null, null, "", "", null);
        try {
            return imageGenerationTool.generate(request, scope);
        } catch (RuntimeException firstFailure) {
            log.warn("process=story_illustration event=retry profile=low_memory reason={}",
                    firstFailure.getMessage());
            if (!imageGenerationTool.awaitProviderRecovery(recoveryTimeout)) {
                log.warn("process=story_illustration event=recovery_timeout timeout={}", recoveryTimeout);
                return null;
            }
            try {
                ImageGenerationRequest fallback = new ImageGenerationRequest(
                        prepared.prompt(), clip(compiled.negative(), maximumPromptCharacters), prepared.facePrompts(),
                        512, 512, 20, null, "", "", null);
                return imageGenerationTool.generate(fallback, scope);
            } catch (RuntimeException fallbackFailure) {
                log.warn("process=story_illustration event=failed reason={}", fallbackFailure.getMessage());
                return null;
            }
        }
    }

    private PreparedPrompt prompt(
            PonyStoryPromptCompiler.CompiledPrompt compiled, String userMessage, String assistantStory) {
        if (fallbackPromptTransformer != null && genericFallback(compiled.positive())) {
            try {
                PonyPromptTransformer.Result transformed = fallbackPromptTransformer.transformWithCharacters(
                        clip((userMessage == null ? "" : userMessage) + "\n"
                                + (assistantStory == null ? "" : assistantStory), maximumPromptCharacters));
                return new PreparedPrompt(
                        clip(transformed.prompt(), maximumPromptCharacters), transformed.facePrompts());
            } catch (RuntimeException exception) {
                log.warn("process=story_visual_prompt event=main_model_fallback_failed reason={}",
                        exception.getMessage());
            }
        }
        return new PreparedPrompt(clip("""
                score_9, score_8_up, score_7_up, story illustration, cinematic composition,
                %s
                """.formatted(compiled.positive()).replaceAll("\\s+", " ").strip(),
                maximumPromptCharacters), compiled.facePrompts());
    }

    private boolean genericFallback(String prompt) {
        return prompt.contains("the story's decisive emotional moment")
                || prompt.contains("opening story moment")
                || prompt.contains("decisive turning point")
                || prompt.contains("emotional ending moment")
                || prompt.contains("symbolic story summary")
                || prompt.contains("character identity study")
                || prompt.contains("the story's final visible moment");
    }

    private String title(StoryIllustrationIntent intent, StoryIllustrationMode mode,
            StorySceneSpec scene, int index, int total) {
        if (intent == StoryIllustrationIntent.DIRECT_IMAGE && mode == StoryIllustrationMode.DECISIVE_SCENE) {
            return "ภาพที่มินิคุงสร้างให้";
        }
        String base = switch (mode) {
            case COVER -> "ภาพปกเรื่องโดยมินิคุง";
            case CHARACTER_PORTRAIT -> "ภาพตัวละครโดยมินิคุง";
            case DECISIVE_SCENE -> "ภาพฉากสำคัญโดยมินิคุง";
            case ENDING_SCENE -> "ภาพฉากจบโดยมินิคุง";
            case STORYBOARD -> "ภาพลำดับที่ " + (index + 1) + "/" + total;
        };
        return scene.title().isBlank() ? base : base + ": " + scene.title();
    }

    private String clip(String value, int maximum) {
        String result = value == null ? "" : value.strip();
        if (result.length() <= maximum) {
            return result;
        }
        return result.substring(0, Math.max(0, maximum - 1)).stripTrailing() + "…";
    }

    private List<CharacterVisualProfile> readMemory(String ownerId, String conversationId) {
        try {
            return characterMemory.find(scope(ownerId, "default"), scope(conversationId, "standalone"));
        } catch (RuntimeException exception) {
            log.warn("process=character_visual_memory event=read_failed reason={}", exception.getMessage());
            return List.of();
        }
    }

    private void saveMemory(String ownerId, String conversationId, List<CharacterVisualProfile> profiles) {
        if (profiles.isEmpty()) return;
        try {
            characterMemory.save(scope(ownerId, "default"), scope(conversationId, "standalone"), profiles);
        } catch (RuntimeException exception) {
            log.warn("process=character_visual_memory event=write_failed reason={}", exception.getMessage());
        }
    }

    private List<CharacterVisualProfile> lockCharacters(
            List<CharacterVisualProfile> remembered,
            List<CharacterVisualProfile> extracted) {
        LinkedHashMap<String, CharacterVisualProfile> merged = new LinkedHashMap<>();
        remembered.forEach(profile -> merged.put(profile.key(), profile));
        extracted.forEach(profile -> merged.merge(profile.key(), profile, CharacterVisualProfile::lockWith));
        return List.copyOf(merged.values());
    }

    private String scope(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static StoryVisualPlanGenerator legacyPlanGenerator(StoryVisualPromptGenerator generator) {
        StoryVisualPromptGenerator safe = Objects.requireNonNull(generator);
        return (userMessage, assistantStory, mode, remembered, maximumStoryboardScenes) -> {
            String visualPrompt = safe.generate(userMessage, assistantStory);
            List<StorySceneSpec> scenes = new ArrayList<>();
            if (mode == StoryIllustrationMode.STORYBOARD) {
                scenes.add(StorySceneSpec.fallback(visualPrompt, "Opening", "opening story moment",
                        "establishing composition", "wide shot"));
                if (maximumStoryboardScenes > 2) {
                    scenes.add(StorySceneSpec.fallback(visualPrompt, "Turning point", "decisive turning point",
                            "dynamic composition", "medium shot"));
                }
                scenes.add(StorySceneSpec.fallback(visualPrompt, "Ending", "emotional ending moment",
                        "resolved composition", "wide shot"));
            } else {
                scenes.add(StorySceneSpec.fallback(visualPrompt, mode.name(), "decisive story moment",
                        "cinematic composition", "medium wide shot"));
            }
            return new StoryVisualPlan(mode, remembered, scenes.stream()
                    .limit(mode == StoryIllustrationMode.STORYBOARD ? maximumStoryboardScenes : 1).toList());
        };
    }

    private record PreparedPrompt(String prompt, List<String> facePrompts) {
        private PreparedPrompt {
            prompt = prompt == null ? "" : prompt;
            facePrompts = facePrompts == null ? List.of() : List.copyOf(facePrompts);
        }
    }

    public record IllustrationResult(List<ChatAttachment> attachments, String notice) {
        public IllustrationResult {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
            notice = notice == null ? "" : notice;
        }

        static IllustrationResult empty() {
            return new IllustrationResult(List.of(), "");
        }

        static IllustrationResult success(List<ChatAttachment> attachments) {
            return new IllustrationResult(attachments, "");
        }

        static IllustrationResult failure(String notice) {
            return new IllustrationResult(List.of(), notice);
        }

        public String appendNoticeTo(String content) {
            return content + notice;
        }
    }
}
