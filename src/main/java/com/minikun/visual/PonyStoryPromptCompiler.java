package com.minikun.visual;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Deterministically compiles validated scene facts and locked character identities into Pony tags. */
public final class PonyStoryPromptCompiler {
    private static final int MAX_TAGS = 110;

    public CompiledPrompt compile(
            StoryIllustrationMode mode,
            StorySceneSpec scene,
            List<CharacterVisualProfile> characters) {
        Map<String, CharacterVisualProfile> profiles = characters.stream()
                .collect(Collectors.toMap(CharacterVisualProfile::key, Function.identity(), (left, right) -> left));
        LinkedHashSet<String> positive = new LinkedHashSet<>();
        LinkedHashSet<String> negative = new LinkedHashSet<>();

        add(positive, treatment(mode));
        List<CharacterVisualProfile> candidates = scene.characterNames().stream()
                .map(name -> profiles.get(key(name))).filter(java.util.Objects::nonNull).toList();
        List<CharacterVisualProfile> referenced = selectCharacters(
                candidates, scene.action() + " " + scene.interaction());
        long girls = referenced.stream().filter(profile -> female(profile.identity())).count();
        long boys = referenced.stream().filter(profile -> male(profile.identity())).count();
        if (girls > 0) add(positive, girls + (girls == 1 ? "girl" : "girls"));
        if (boys > 0) add(positive, boys + (boys == 1 ? "boy" : "boys"));
        int subjectCount = referenced.isEmpty() ? Math.min(scene.subjectCount(), 2) : referenced.size();
        if (girls == 0 && boys == 0 && subjectCount == 1) add(positive, "solo");
        if (girls == 0 && boys == 0 && subjectCount > 1) {
            add(positive, subjectCount + " subjects");
        }
        for (int index = 0; index < referenced.size(); index++) {
            CharacterVisualProfile profile = referenced.get(index);
            addCharacterBlock(positive, profile, index, referenced.size());
            addAll(negative, profile.negativeTags());
        }
        List<String> omittedNames = candidates.stream().filter(profile -> !referenced.contains(profile))
                .map(CharacterVisualProfile::name).toList();
        add(positive, anonymize(scene.action(), referenced, omittedNames));
        add(positive, anonymize(scene.interaction(), referenced, omittedNames));
        addAll(positive, scene.keyObjects());
        add(positive, scene.setting());
        add(positive, scene.time());
        add(positive, scene.weather());
        add(positive, scene.emotion());
        add(positive, scene.atmosphere());
        add(positive, scene.lighting());
        add(positive, scene.palette());
        add(positive, scene.composition());
        add(positive, scene.cameraAngle());
        add(positive, scene.shotDistance());
        add(positive, scene.focus());
        addAll(positive, scene.mustInclude());
        addAll(positive, scene.fineDetails());
        addAll(negative, scene.mustNotInclude());
        return new CompiledPrompt(join(positive), join(negative), facePrompts(referenced));
    }

    private List<CharacterVisualProfile> selectCharacters(
            List<CharacterVisualProfile> characters, String action) {
        if (characters.size() <= 2) return characters;
        List<CharacterVisualProfile> ranked = new ArrayList<>(characters);
        ranked.sort(java.util.Comparator
                .comparingInt((CharacterVisualProfile profile) -> mentions(action, profile.name()) ? 1 : 0)
                .reversed()
                .thenComparingInt(characters::indexOf));
        java.util.Set<CharacterVisualProfile> selected = new java.util.HashSet<>(ranked.subList(0, 2));
        return characters.stream().filter(selected::contains).toList();
    }

    private void addCharacterBlock(
            LinkedHashSet<String> target, CharacterVisualProfile character, int index, int total) {
        List<String> tags = new ArrayList<>();
        String position = position(character, index, total);
        if (!position.isBlank()) tags.add(position);
        if (!character.identity().isBlank()) tags.add(character.identity());
        tags.addAll(character.appearance());
        tags.addAll(character.clothing());
        tags.addAll(character.accessories());
        tags.addAll(character.canonicalTags());
        if (!tags.isEmpty() && target.size() < MAX_TAGS) {
            target.add("(" + String.join(", ", tags) + ":" + (index == 0 ? "1.3" : "1.2") + ")");
        }
    }

    private String anonymize(
            String value, List<CharacterVisualProfile> selected, List<String> omittedNames) {
        if (value == null || value.isBlank()) return "";
        if (omittedNames.stream().anyMatch(name -> mentions(value, name))) return "";
        String result = value;
        for (int index = 0; index < selected.size(); index++) {
            CharacterVisualProfile character = selected.get(index);
            result = replaceName(result, character.name(), position(character, index, selected.size()));
        }
        return result;
    }

    private boolean mentions(String value, String name) {
        String source = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return aliases(name).stream().anyMatch(alias -> source.matches(
                ".*(?:^|[^a-z0-9])" + java.util.regex.Pattern.quote(alias) + "(?:$|[^a-z0-9]).*"));
    }

    private String replaceName(String value, String name, String replacement) {
        String result = value;
        for (String alias : aliases(name)) {
            result = result.replaceAll(
                    "(?i)(?<![a-z0-9])" + java.util.regex.Pattern.quote(alias) + "(?![a-z0-9])",
                    java.util.regex.Matcher.quoteReplacement(replacement));
        }
        return result.replaceAll("\\s+", " ").strip();
    }

    private List<String> aliases(String name) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT).strip();
        if (!normalized.isBlank()) aliases.add(normalized);
        for (String part : normalized.split(" ")) if (part.length() >= 3) aliases.add(part);
        return aliases.stream().sorted(java.util.Comparator.comparingInt(String::length).reversed()).toList();
    }

    private List<String> facePrompts(List<CharacterVisualProfile> characters) {
        List<String> prompts = new ArrayList<>();
        for (int index = 0; index < characters.size(); index++) {
            CharacterVisualProfile character = characters.get(index);
            LinkedHashSet<String> tags = new LinkedHashSet<>();
            add(tags, "score_9, score_8_up, score_7_up, detailed face");
            add(tags, position(character, index, characters.size()));
            addIdentity(tags, character.identity(), 1);
            addAll(tags, character.appearance());
            addAll(tags, character.accessories());
            addAll(tags, character.canonicalTags());
            prompts.add(join(tags));
        }
        return List.copyOf(prompts);
    }

    private String position(CharacterVisualProfile character, int index, int total) {
        if (total < 2) return "";
        String subject = female(character.identity()) ? "girl" : male(character.identity()) ? "boy" : "person";
        if (total == 2) return (index == 0 ? "left " : "right ") + subject;
        if (total == 3) return switch (index) {
            case 0 -> "left " + subject;
            case 1 -> "center " + subject;
            default -> "right " + subject;
        };
        if (index == 0) return "leftmost " + subject;
        if (index == total - 1) return "rightmost " + subject;
        return (index + 1) + "th " + subject + " from left";
    }

    private String treatment(StoryIllustrationMode mode) {
        return switch (mode) {
            case COVER -> "story cover art, iconic ensemble composition";
            case DECISIVE_SCENE -> "decisive story moment, cinematic composition";
            case CHARACTER_PORTRAIT -> "character portrait, identity-focused composition";
            case ENDING_SCENE -> "final story scene, emotional resolution, cinematic composition";
            case STORYBOARD -> "storyboard panel, sequential narrative composition";
        };
    }

    private void addAll(LinkedHashSet<String> target, List<String> values) {
        if (values != null) values.forEach(value -> add(target, value));
    }

    private void addIdentity(LinkedHashSet<String> target, String identity, int subjectCount) {
        if (identity == null) return;
        for (String value : identity.split(",")) {
            String tag = value.strip();
            if (subjectCount > 1 && tag.matches("(?i)[1-8](?:girls?|boys?)")) continue;
            add(target, tag);
        }
    }

    private boolean female(String identity) {
        String value = identity == null ? "" : identity.toLowerCase(Locale.ROOT);
        return value.contains("girl") || value.contains("woman") || value.contains("female");
    }

    private boolean male(String identity) {
        String value = identity == null ? "" : identity.toLowerCase(Locale.ROOT);
        if (value.contains("female")) return false;
        return value.contains("boy") || value.contains(" man") || value.startsWith("man")
                || value.matches(".*(?:^|\\W)male(?:\\W|$).*");
    }

    private void add(LinkedHashSet<String> target, String value) {
        if (value == null) return;
        for (String raw : value.split(",")) {
            String tag = raw.replaceAll("^[\\s#*:-]+|[\\s#*:-]+$", "")
                    .replaceAll("\\s+", " ").strip();
            String lower = tag.toLowerCase(Locale.ROOT);
            if (tag.isBlank() || lower.equals("none") || lower.equals("unknown")
                    || lower.equals("source_anime") || lower.startsWith("q:")
                    || lower.contains("how to get") || lower.contains("write a function")) continue;
            if (target.size() < MAX_TAGS) target.add(tag);
        }
    }

    private String join(LinkedHashSet<String> values) {
        return String.join(", ", new ArrayList<>(values));
    }

    private String key(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    public record CompiledPrompt(String positive, String negative, List<String> facePrompts) {
        public CompiledPrompt {
            facePrompts = facePrompts == null ? List.of() : List.copyOf(facePrompts);
        }
    }
}
