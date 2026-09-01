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
        List<CharacterVisualProfile> referenced = scene.characterNames().stream()
                .map(name -> profiles.get(key(name))).filter(java.util.Objects::nonNull).toList();
        long girls = referenced.stream().filter(profile -> female(profile.identity())).count();
        long boys = referenced.stream().filter(profile -> male(profile.identity())).count();
        if (girls > 0) add(positive, girls + (girls == 1 ? "girl" : "girls"));
        if (boys > 0) add(positive, boys + (boys == 1 ? "boy" : "boys"));
        if (girls == 0 && boys == 0 && scene.subjectCount() == 1) add(positive, "solo");
        if (girls == 0 && boys == 0 && scene.subjectCount() > 1) {
            add(positive, scene.subjectCount() + " subjects");
        }
        for (String characterName : scene.characterNames()) {
            CharacterVisualProfile profile = profiles.get(key(characterName));
            if (profile == null) {
                add(positive, characterName);
                continue;
            }
            addIdentity(positive, profile.identity(), scene.subjectCount());
            addAll(positive, profile.appearance());
            addAll(positive, profile.clothing());
            addAll(positive, profile.accessories());
            addAll(positive, profile.canonicalTags());
            addAll(negative, profile.negativeTags());
        }
        add(positive, scene.action());
        add(positive, scene.interaction());
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
        return new CompiledPrompt(join(positive), join(negative));
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

    public record CompiledPrompt(String positive, String negative) {}
}
