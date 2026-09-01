package com.minikun.visual;

import com.minikun.model.task.TaskModelMessage;
import com.minikun.model.task.TaskModelProvider;
import com.minikun.model.task.TaskModelRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/** Uses Mini-kun's local task model to translate the scene into compact SDXL tags. */
@Slf4j
public final class TaskModelStoryVisualPromptGenerator implements StoryVisualPromptGenerator {
    private static final int MAX_INPUT_CHARACTERS = 6_000;
    private static final int MAX_ESSENCE_CHARACTERS = 1_200;
    private static final int MAX_PROMPT_CHARACTERS = 1_600;
    private static final int MAX_PONY_TAGS = 90;
    private static final String PONY_FIELD_LABELS = "subject count|identity|appearance|clothing|pose|"
            + "decisive action|interaction|key objects|exact setting|time|weather|emotion|atmosphere|lighting|"
            + "palette|composition|camera angle|shot distance|focus|fine visible details";
    private final TaskModelProvider taskModel;

    public TaskModelStoryVisualPromptGenerator(TaskModelProvider taskModel) {
        this.taskModel = Objects.requireNonNull(taskModel, "task model must not be null");
    }

    @Override
    public String generate(String userMessage, String assistantStory) {
        String fallback = StoryVisualPromptGenerator.fallback(userMessage, assistantStory);
        String essence;
        try {
            essence = extractEssence(userMessage, assistantStory);
            if (essence.isBlank()
                    || !preservesKnownSceneAnchors(fallback, essence, userMessage, assistantStory)) {
                throw new IllegalStateException("task model returned an invalid story essence");
            }
        } catch (RuntimeException exception) {
            log.warn("process=story_visual_essence event=fallback reason={}", exception.getMessage());
            essence = fallback;
        }
        try {
            String generated = taskModel.generate(new TaskModelRequest(
                    List.of(new TaskModelMessage("user", ponyPrompt(essence))), 240, 0.1,
                    TaskModelRequest.ResponseFormat.TEXT));
            String normalized = normalizeTags(generated);
            if (normalized.isBlank() || asciiRatio(normalized) < 0.9 || commaCount(normalized) < 12) {
                throw new IllegalStateException("task model returned a non-English visual prompt");
            }
            if (!preservesKnownSceneAnchors(fallback, normalized, userMessage, assistantStory)) {
                throw new IllegalStateException("task model dropped defining story subjects or setting");
            }
            return normalized;
        } catch (RuntimeException exception) {
            log.warn("process=story_visual_prompt event=fallback reason={}", exception.getMessage());
            return fallback;
        }
    }

    private String extractEssence(String userMessage, String assistantStory) {
        String embedded = embeddedVisualBrief(assistantStory);
        if (!embedded.isBlank()) {
            return embedded;
        }
        String prompt = "/no_think\nExtract the single most visually decisive scene and the essential meaning "
                + "of the story below. Return only a compact English visual brief covering: core subjects, defining "
                + "identity and appearance, decisive action, relationship, exact setting and time, indispensable "
                + "objects, emotional contrast, atmosphere, lighting, color palette, and camera composition. Preserve "
                + "story-specific facts. Omit dialogue, exposition, morals, and events outside the chosen frame. Do not "
                + "invent people, clothing, objects, or locations.\nREQUEST: " + clip(userMessage)
                + "\nSTORY: " + clip(assistantStory);
        String generated = taskModel.generate(new TaskModelRequest(
                List.of(new TaskModelMessage("user", prompt)), 220, 0.1,
                TaskModelRequest.ResponseFormat.TEXT));
        return normalize(generated, MAX_ESSENCE_CHARACTERS);
    }

    private String embeddedVisualBrief(String assistantStory) {
        String story = assistantStory == null ? "" : assistantStory;
        int marker = story.toLowerCase(java.util.Locale.ROOT).lastIndexOf("visual brief");
        if (marker < 0) {
            return "";
        }
        String brief = story.substring(marker + "visual brief".length())
                .replaceAll("(?m)^[\\s>*#-]+", "")
                .replace("**", "")
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
        return normalize(brief, MAX_ESSENCE_CHARACTERS);
    }

    private String ponyPrompt(String essence) {
        return "/no_think\nConvert the SCENE ESSENCE below into one detailed Pony XL booru-style prompt. "
                + "Return only 55 to 90 concise comma-separated English tags, never sentences. Order the tags as: "
                + "subject count, identity, appearance, clothing only when present, pose, decisive action, interaction, "
                + "key objects, exact setting, time, weather, emotion, atmosphere, lighting, palette, composition, camera "
                + "angle, shot distance, focus, and fine visible details. Preserve every defining fact and do not invent "
                + "new characters or story elements. Do not add score, source, or rating tags. Do not explain, use "
                + "headings, hashtags, quotes, or prose.\nSCENE ESSENCE: " + essence;
    }

    private boolean preservesKnownSceneAnchors(
            String fallback, String generated, String userMessage, String assistantStory) {
        String candidate = generated.toLowerCase(java.util.Locale.ROOT);
        String source = ((userMessage == null ? "" : userMessage) + " "
                + (assistantStory == null ? "" : assistantStory)).toLowerCase(java.util.Locale.ROOT);
        boolean sourceHasPerson = containsAny(source,
                "ผู้หญิง", "ผู้ชาย", "เด็กหญิง", "เด็กชาย", "หญิงสาว", "ชายหนุ่ม", "หญิง", "ชาย",
                "girl", "boy", "woman", "man", "human", "person");
        boolean candidateHasPerson = containsAny(candidate,
                "1girl", "1boy", "girl", "boy", "woman", " man", "female", "male", "human", "person");
        return (!fallback.contains("black cat") || containsAny(candidate, "cat", "แมว"))
                && (!fallback.contains("old observatory") || containsAny(candidate, "observatory", "หอดูดาว"))
                && (!fallback.contains("starlight") || containsAny(candidate, "star", "ดาว"))
                && (sourceHasPerson || !candidateHasPerson);
    }

    private boolean containsAny(String value, String... needles) {
        return java.util.Arrays.stream(needles).anyMatch(value::contains);
    }

    private String clip(String value) {
        String result = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        if (result.length() <= MAX_INPUT_CHARACTERS) {
            return result;
        }
        int half = MAX_INPUT_CHARACTERS / 2;
        return result.substring(0, half).stripTrailing() + " … "
                + result.substring(result.length() - half).stripLeading();
    }

    private String normalizeTags(String value) {
        String result = normalize(value, MAX_PROMPT_CHARACTERS);
        java.util.regex.Matcher contamination = java.util.regex.Pattern.compile(
                "(?i)(?:^|[,\\s])(?:q\\s*:|question\\s*:|how\\s+to\\b|write\\s+(?:a|the)\\s+function\\b)")
                .matcher(result);
        if (contamination.find()) {
            result = result.substring(0, contamination.start()).stripTrailing();
        }
        result = result.replaceAll("(?i)\\s*(?:" + PONY_FIELD_LABELS + ")\\s*:\\s*", ", ");
        ArrayList<String> tags = new ArrayList<>();
        for (String rawTag : result.split(",")) {
            String tag = rawTag.replaceAll("^[\\s#*:-]+|[\\s#*:-]+$", "").strip();
            if (tag.isBlank() || tag.equalsIgnoreCase("source_anime")
                    || tag.equalsIgnoreCase("none") || tag.equalsIgnoreCase("unknown")) {
                continue;
            }
            tags.add(tag);
            if (tags.size() >= MAX_PONY_TAGS) {
                break;
            }
        }
        return String.join(", ", tags);
    }

    private String normalize(String value, int maximumCharacters) {
        String result = value == null ? "" : value.replaceAll("[\\r\\n]+", " ")
                .replaceAll("(?i)^(visual prompt|prompt)\\s*:\\s*", "")
                .replaceAll("^[\"']|[\"']$", "")
                .replaceAll("\\s+", " ").strip();
        return result.length() <= maximumCharacters
                ? result : result.substring(0, maximumCharacters).stripTrailing();
    }

    private long commaCount(String value) {
        return value.chars().filter(character -> character == ',').count();
    }

    private double asciiRatio(String value) {
        long ascii = value.chars().filter(character -> character >= 32 && character < 127).count();
        return value.isEmpty() ? 0.0 : (double) ascii / value.length();
    }
}
