package com.minikun.visual;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/** Fail-open post-processor that turns a story's defining moment into an attachment. */
@Slf4j
public final class StoryIllustrationService {
    static final String FAILURE_NOTICE = "\n\n> ⚠️ มินิคุงสร้างภาพประกอบไม่สำเร็จ"
            + " กรุณาลองอีกครั้งเมื่อ TinyGrad พร้อมใช้งานครับ";

    private final ImageGenerationTool imageGenerationTool;
    private final StoryIllustrationIntentDetector intentDetector;
    private final boolean autoIllustrateCreativeStories;
    private final int maximumPromptCharacters;
    private final StoryVisualPlanGenerator visualPlanGenerator;
    private final CharacterVisualMemory characterMemory;
    private final PonyStoryPromptCompiler promptCompiler;
    private final StoryIllustrationModeDetector modeDetector;
    private final int maximumStoryboardScenes;
    private final PonyPromptTransformer fallbackPromptTransformer;

    public StoryIllustrationService(
            ImageGenerationTool imageGenerationTool,
            boolean autoIllustrateCreativeStories,
            int maximumPromptCharacters,
            StoryVisualPlanGenerator visualPlanGenerator,
            CharacterVisualMemory characterMemory,
            int maximumStoryboardScenes) {
        this(imageGenerationTool, autoIllustrateCreativeStories, maximumPromptCharacters,
                visualPlanGenerator, characterMemory, maximumStoryboardScenes, null);
    }

    public StoryIllustrationService(
            ImageGenerationTool imageGenerationTool,
            boolean autoIllustrateCreativeStories,
            int maximumPromptCharacters,
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
        if (intent == StoryIllustrationIntent.DIRECT_IMAGE && fallbackPromptTransformer != null) {
            return fallbackIllustration(
                    intent, mode, ownerId, conversationId, userMessage, assistantStory);
        }
        List<CharacterVisualProfile> remembered = readMemory(ownerId, conversationId);
        StoryVisualPlan plan;
        try {
            plan = Objects.requireNonNull(visualPlanGenerator.generate(
                    userMessage, assistantStory, mode, remembered, maximumStoryboardScenes),
                    "visual planner returned null");
        } catch (RuntimeException exception) {
            log.warn("process=story_visual_plan event=failed mode={} reason={}", mode, exception.getMessage());
            return fallbackIllustration(
                    intent, mode, ownerId, conversationId, userMessage, assistantStory);
        }
        List<CharacterVisualProfile> lockedCharacters = lockCharacters(remembered, plan.characters());
        saveMemory(ownerId, conversationId, lockedCharacters);

        List<ChatAttachment> attachments = new ArrayList<>();
        for (int index = 0; index < plan.scenes().size(); index++) {
            StorySceneSpec scene = plan.scenes().get(index);
            PonyStoryPromptCompiler.CompiledPrompt compiled = promptCompiler.compile(
                    mode, scene, lockedCharacters);
            String attachmentTitle = title(intent, mode, scene, index, plan.scenes().size());
            ImageGenerationTool.Generation generated = generateScene(
                    compiled, ownerId, conversationId, mode, attachmentTitle);
            if (generated == null) {
                return attachments.isEmpty()
                        ? IllustrationResult.failure(FAILURE_NOTICE)
                        : new IllustrationResult(attachments, FAILURE_NOTICE);
            }
            attachments.add(attachment(attachmentTitle, generated));
        }
        return IllustrationResult.success(attachments);
    }

    private IllustrationResult fallbackIllustration(
            StoryIllustrationIntent intent,
            StoryIllustrationMode mode,
            String ownerId,
            String conversationId,
            String userMessage,
            String assistantStory) {
        int total = mode == StoryIllustrationMode.STORYBOARD ? maximumStoryboardScenes : 1;
        List<ChatAttachment> attachments = new ArrayList<>();
        boolean usedDeterministicPrompt = false;
        for (int index = 0; index < total; index++) {
            PonyPromptTransformer.Result prompt;
            String brief = fallbackBrief(userMessage, assistantStory, mode, index, total);
            try {
                if (fallbackPromptTransformer == null) throw new IllegalStateException("main model is unavailable");
                prompt = StoryGenderGuard.correct(
                        brief, fallbackPromptTransformer.transformWithCharacters(brief));
                StoryGenderGuard.validate(brief, prompt.prompt(), List.of());
            } catch (RuntimeException exception) {
                usedDeterministicPrompt = true;
                log.warn("process=story_visual_plan event=prompt_fallback panel={} reason={}",
                        index + 1, exception.getMessage());
                prompt = StoryGenderGuard.correct(brief,
                        new PonyPromptTransformer.Result(
                                deterministicPrompt(userMessage, assistantStory, mode), List.of()));
            }
            String title = fallbackTitle(intent, mode, index, total);
            String positive = mode == StoryIllustrationMode.STORYBOARD
                    ? prompt.prompt() + ", " + storyboardBeat(index, total)
                    : prompt.prompt();
            ImageGenerationTool.Generation generated = generateScene(
                    new PonyStoryPromptCompiler.CompiledPrompt(positive, "", prompt.facePrompts()),
                    ownerId, conversationId, mode, title);
            if (generated == null) {
                return attachments.isEmpty() ? IllustrationResult.failure(FAILURE_NOTICE)
                        : new IllustrationResult(attachments, FAILURE_NOTICE);
            }
            attachments.add(attachment(title, generated));
        }
        log.info("process=story_visual_plan event=fallback_completed mode={} source={}", mode,
                usedDeterministicPrompt ? "mixed_or_deterministic" : "main_model");
        return IllustrationResult.success(attachments);
    }

    private String fallbackBrief(String userMessage, String assistantStory,
            StoryIllustrationMode mode, int index, int total) {
        String user = userMessage == null ? "" : userMessage;
        String story = assistantStory == null ? "" : assistantStory.replaceAll("\\s+", " ").strip();
        if (mode != StoryIllustrationMode.STORYBOARD) {
            return clip("USER VISUAL REQUEST (authoritative constraints): " + user
                    + "\nASSISTANT VISUAL HANDOFF (observed facts and concrete changes): " + story);
        }
        if (story.isBlank()) {
            return clip("USER VISUAL REQUEST (authoritative constraints): " + user
                    + "\nSTORY FACTS (apply to every panel): " + user
                    + "\nPANEL " + (index + 1) + "/" + total + " " + storyboardBeat(index, total));
        }
        List<String> units = java.util.Arrays.stream(story.split("[.!?…。！？]+\\s*"))
                .filter(value -> !value.isBlank()).toList();
        if (units.size() < total) units = List.of(story.split("\\s+"));
        int start = units.size() * index / total;
        int end = units.size() * (index + 1) / total;
        String excerpt = start < end ? String.join(" ", units.subList(start, end)) : story;
        return clip("USER VISUAL REQUEST (authoritative constraints): " + user
                + "\nSTORY FACTS (apply to every panel): " + story
                + "\nPANEL " + (index + 1) + "/" + total + " " + storyboardBeat(index, total)
                + ": " + excerpt);
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
            String sceneTitle) {
        ImageGenerationScope scope = new ImageGenerationScope(
                ownerId, conversationId, "generated", mode.name().toLowerCase(java.util.Locale.ROOT), sceneTitle);
        ImageGenerationRequest request = new ImageGenerationRequest(
                compiled.positive(), compiled.negative(), compiled.facePrompts(),
                null, null, null, null, "", "", null);
        try {
            return imageGenerationTool.generate(request, scope);
        } catch (IllegalArgumentException invalidPrompt) {
            log.warn("process=story_illustration event=invalid_prompt reason={}", invalidPrompt.getMessage());
            return null;
        } catch (RuntimeException failure) {
            log.warn("process=story_illustration event=failed reason={}", failure.getMessage());
            return null;
        }
    }

    private String deterministicPrompt(
            String userMessage, String assistantStory, StoryIllustrationMode mode) {
        String source = ((userMessage == null ? "" : userMessage) + " "
                + (assistantStory == null ? "" : assistantStory)).toLowerCase(java.util.Locale.ROOT);
        List<String> tags = new ArrayList<>();
        if (source.contains("แมวดำ") || source.contains("แมวสีดำ") || source.contains("black cat")) {
            tags.add("black cat");
        } else if (source.contains("แมวขาว") || source.contains("แมวสีขาว") || source.contains("white cat")) {
            tags.add("white cat");
        } else if (source.contains("แมว") || source.contains("cat")) {
            tags.add("cat");
        }
        if (source.contains("หอดูดาว") || source.contains("observatory")) tags.add("observatory");
        if (source.replace("หอดูดาว", "").contains("ดาว") || source.contains("star")) tags.add("starlight");
        tags.add(switch (mode) {
            case COVER -> "story cover art";
            case CHARACTER_PORTRAIT -> "character portrait";
            case ENDING_SCENE -> "emotional ending scene";
            case STORYBOARD -> "storyboard illustration";
            case DECISIVE_SCENE -> "decisive story moment";
        });
        tags.add("cinematic lighting");
        tags.add("clear subject and detailed background");
        return "score_9, score_8_up, score_7_up, score_6_up, rating_safe, "
                + String.join(", ", tags);
    }

    private String fallbackTitle(
            StoryIllustrationIntent intent, StoryIllustrationMode mode, int index, int total) {
        if (intent == StoryIllustrationIntent.DIRECT_IMAGE && mode == StoryIllustrationMode.DECISIVE_SCENE) {
            return "ภาพที่มินิคุงสร้างให้";
        }
        return switch (mode) {
            case COVER -> "ภาพปกเรื่องโดยมินิคุง";
            case CHARACTER_PORTRAIT -> "ภาพตัวละครโดยมินิคุง";
            case DECISIVE_SCENE -> "ภาพฉากสำคัญโดยมินิคุง";
            case ENDING_SCENE -> "ภาพฉากจบโดยมินิคุง";
            case STORYBOARD -> "ภาพลำดับที่ " + (index + 1) + "/" + total;
        };
    }

    private String storyboardBeat(int index, int total) {
        if (index == 0) return "opening story moment";
        if (index == total - 1) return "emotional ending moment";
        return "story turning point, panel " + (index + 1) + " of " + total;
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

    private String clip(String value) {
        String result = value == null ? "" : value.strip();
        return result.length() <= maximumPromptCharacters ? result
                : result.substring(0, maximumPromptCharacters - 1).stripTrailing() + "…";
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
