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
    private static final String QUALITY_PREFIX = "score_9, score_8_up, score_7_up, score_6_up";
    private static final java.util.regex.Pattern ANIMAL_IDENTITY = java.util.regex.Pattern.compile(
            "(?<![a-z0-9])(?:cats?|dogs?|animals?)(?![a-z0-9])");

    public CompiledPrompt compile(
            StoryIllustrationMode mode,
            StorySceneSpec scene,
            List<CharacterVisualProfile> characters) {
        Map<String, CharacterVisualProfile> profiles = characters.stream()
                .collect(Collectors.toMap(CharacterVisualProfile::key, Function.identity(), (left, right) -> left));
        LinkedHashSet<String> boosters = new LinkedHashSet<>();
        LinkedHashSet<String> negative = new LinkedHashSet<>();

        List<CharacterVisualProfile> candidates = scene.characterNames().stream()
                .map(name -> profiles.get(key(name))).filter(java.util.Objects::nonNull).toList();
        if (candidates.isEmpty() && scene.subjectCount() > 0
                && scene.subjectCount() == characters.size()) {
            candidates = List.copyOf(characters);
        }
        List<CharacterVisualProfile> referenced = selectCharacters(
                candidates, scene.action() + " " + scene.interaction());
        long girls = referenced.stream().filter(profile -> female(profile.identity())).count();
        long boys = referenced.stream().filter(profile -> male(profile.identity())).count();
        if (girls > 0) add(boosters, girls + (girls == 1 ? "girl" : "girls"));
        if (boys > 0) add(boosters, boys + (boys == 1 ? "boy" : "boys"));
        if (girls > 0 && boys == 0) add(negative, "man, men, boy, boys, male, 1boy");
        if (boys > 0 && girls == 0) add(negative, "woman, women, girl, girls, female, 1girl");
        int subjectCount = referenced.isEmpty() ? scene.subjectCount() : referenced.size();
        if (girls == 0 && boys == 0 && subjectCount == 1) add(boosters, "solo");
        if (girls == 0 && boys == 0 && subjectCount > 1) {
            add(boosters, subjectCount + " subjects");
        }
        for (int index = 0; index < referenced.size(); index++) {
            CharacterVisualProfile profile = referenced.get(index);
            addCharacterBlock(boosters, profile, index, referenced.size());
            addAll(negative, profile.negativeTags());
        }
        if (!referenced.isEmpty()
                && referenced.stream().allMatch(profile -> animal(profile.identity()))) {
            add(boosters, "animal focus, no humans");
            add(negative, "human, person, woman, man, girl, boy, 1girl, 1boy");
        }
        List<String> omittedNames = candidates.stream().filter(profile -> !referenced.contains(profile))
                .map(CharacterVisualProfile::name).toList();
        String factual = factualDescription(scene, referenced, omittedNames, subjectCount);
        String style = styleDescription(mode, scene);
        addAll(negative, scene.mustNotInclude());
        List<String> sections = new ArrayList<>(List.of(QUALITY_PREFIX, rating(scene), factual, style));
        if (!boosters.isEmpty()) sections.add(join(boosters));
        String positive = String.join(", ", sections);
        return new CompiledPrompt(positive, join(negative), facePrompts(referenced));
    }

    private String factualDescription(
            StorySceneSpec scene,
            List<CharacterVisualProfile> referenced,
            List<String> omittedNames,
            int subjectCount) {
        String who = referenced.isEmpty()
                ? (subjectCount > 0 ? subjectCount + " visible subjects" : "the visible subjects")
                : referenced.stream()
                        .map(profile -> characterDescription(profile, referenced.indexOf(profile), referenced.size()))
                        .filter(value -> !value.isBlank())
                        .collect(Collectors.joining("; "));
        if (who.isBlank()) who = "the visible subjects";

        LinkedHashSet<String> actions = new LinkedHashSet<>();
        addPhrase(actions, anonymize(scene.action(), referenced, omittedNames), referenced.isEmpty());
        addPhrase(actions, anonymize(scene.interaction(), referenced, omittedNames), false);
        addPhrases(actions, scene.keyObjects(), false);
        addPhrases(actions, scene.mustInclude(), false);
        addPhrases(actions, scene.fineDetails(), false);
        String what = actions.isEmpty() ? "are present in the frame" : String.join(", ", actions);

        LinkedHashSet<String> location = new LinkedHashSet<>();
        addPhrase(location, scene.setting(), false);
        addPhrase(location, scene.time(), false);
        addPhrase(location, scene.weather(), false);
        location.removeIf(actions::contains);
        StringBuilder result = new StringBuilder("A detailed story moment shows ")
                .append(who).append(". The visible story action is ").append(what).append(".");
        if (!location.isEmpty()) {
            result.append(" The visible scene is ").append(String.join(", ", location)).append(".");
        }
        return result.toString();
    }

    private String styleDescription(StoryIllustrationMode mode, StorySceneSpec scene) {
        LinkedHashSet<String> style = new LinkedHashSet<>();
        style.add("digital anime illustration");
        addPhrase(style, modeLabel(mode), false);
        addPhrase(style, scene.emotion(), false);
        addPhrase(style, scene.atmosphere(), false);
        addPhrase(style, scene.lighting(), false);
        addPhrase(style, scene.palette(), false);
        addPhrase(style, scene.composition(), false);
        addPhrase(style, scene.cameraAngle(), false);
        addPhrase(style, scene.shotDistance(), false);
        addPhrase(style, scene.focus(), false);
        return "Style: " + String.join(", ", style) + ".";
    }

    private String characterDescription(CharacterVisualProfile character, int index, int total) {
        String base = female(character.identity()) ? "girl"
                : male(character.identity()) ? "boy"
                : animal(character.identity()) ? "animal" : "person";
        String position = position(character, index, total);
        if (!position.isBlank()) base = position;
        LinkedHashSet<String> details = new LinkedHashSet<>();
        addPhrases(details, character.appearance(), false);
        addPhrases(details, character.clothing(), false);
        addPhrases(details, character.accessories(), false);
        return details.isEmpty() ? base : base + " with " + String.join(", ", details);
    }

    private void addPhrases(LinkedHashSet<String> target, List<String> values, boolean stripSubject) {
        if (values != null) values.forEach(value -> addPhrase(target, value, stripSubject));
    }

    private void addPhrase(LinkedHashSet<String> target, String value, boolean stripSubject) {
        String phrase = safePhrase(value);
        if (stripSubject) {
            phrase = phrase.replaceFirst("^the\\s+cat\\s+", "");
        }
        if (!phrase.isBlank()) target.add(phrase);
    }

    private String safePhrase(String value) {
        if (value == null || value.isBlank()) return "";
        String phrase = value.replaceAll("\\([^)]*\\)", "")
                .replaceAll("[.!?]+$", "")
                .replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
        if (phrase.isBlank() || phrase.matches(".*[<>\\[\\]{}].*")
                || phrase.chars().anyMatch(codePoint -> codePoint > 127 && Character.isLetter(codePoint))
                || phrase.contains("how to get") || phrase.contains("write a function")) {
            return "";
        }
        return phrase;
    }

    private String modeLabel(StoryIllustrationMode mode) {
        return switch (mode) {
            case COVER -> "story cover art";
            case DECISIVE_SCENE -> "decisive story moment";
            case CHARACTER_PORTRAIT -> "character portrait";
            case ENDING_SCENE -> "final story scene";
            case STORYBOARD -> "storyboard panel";
        };
    }

    private String rating(StorySceneSpec scene) {
        String source = (scene.action() + " " + scene.interaction() + " "
                + String.join(" ", scene.mustInclude()) + " "
                + String.join(" ", scene.fineDetails())).toLowerCase(Locale.ROOT);
        if (source.matches(".*(?:\\bnude\\b|\\bnaked\\b|\\bsex\\b|\\bgenital\\w*\\b|"
                + "\\bpenetrat\\w*\\b|\\bmasturbat\\w*\\b|\\bcum\\b).*")) {
            return "rating_explicit";
        }
        if (source.matches(".*(?:\\blingerie\\b|\\bunderwear\\b|\\blatex\\b|\\bnsfw\\b|"
                + "\\bkiss\\w*\\b|\\byuri\\b|\\bcleavage\\b|\\bfetish\\b).*")) {
            return "rating_questionable";
        }
        return "rating_safe";
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
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        add(tags, position(character, index, total));
        addIdentity(tags, character.identity(), 1);
        addAll(tags, character.appearance());
        addAll(tags, character.clothing());
        addAll(tags, character.accessories());
        addAll(tags, character.canonicalTags());
        if (!tags.isEmpty()) {
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
            if (animal(character.identity())) continue;
            LinkedHashSet<String> tags = new LinkedHashSet<>();
            add(tags, QUALITY_PREFIX + ", rating_safe, detailed face");
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
        String subject = female(character.identity()) ? "girl"
                : male(character.identity()) ? "boy"
                : animal(character.identity()) ? "animal" : "person";
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

    private void addAll(LinkedHashSet<String> target, List<String> values) {
        if (values != null) values.forEach(value -> add(target, value));
    }

    private void addIdentity(LinkedHashSet<String> target, String identity, int subjectCount) {
        if (identity == null) return;
        if (female(identity)) {
            add(target, "1girl");
            return;
        }
        if (male(identity)) {
            add(target, "1boy");
            return;
        }
        for (String value : identity.split(",")) {
            String tag = value.strip();
            if (subjectCount > 1 && tag.matches("(?i)[1-8](?:girls?|boys?)")) continue;
            add(target, tag);
        }
    }

    private boolean female(String identity) {
        String value = identity == null ? "" : identity.toLowerCase(Locale.ROOT);
        return value.contains("girl") || value.contains("woman") || value.contains("female")
                || value.contains("ผู้หญิง") || value.contains("หญิง") || value.contains("สาว");
    }

    private boolean male(String identity) {
        String value = identity == null ? "" : identity.toLowerCase(Locale.ROOT);
        if (value.contains("female")) return false;
        return value.contains("boy") || value.contains(" man") || value.startsWith("man")
                || value.matches(".*(?:^|\\W)male(?:\\W|$).*")
                || value.contains("ผู้ชาย") || value.contains("ชาย");
    }

    private boolean animal(String identity) {
        String value = identity == null ? "" : identity.toLowerCase(Locale.ROOT);
        return ANIMAL_IDENTITY.matcher(value).find() && !female(value) && !male(value);
    }

    private void add(LinkedHashSet<String> target, String value) {
        if (value == null) return;
        for (String raw : value.split(",")) {
            String tag = raw.replaceAll("^[\\s#*:-]+|[\\s#*:-]+$", "")
                    .replaceAll("\\s+", " ").strip();
            if (tag.matches(".*[.!?]$") || tag.matches(".*\\([^)]*\\).*")) continue;
            String lower = tag.toLowerCase(Locale.ROOT);
            if (tag.isBlank() || lower.equals("none") || lower.equals("unknown")
                    || lower.equals("indeterminate") || lower.equals("unspecified")
                    || forbiddenTag(lower) || lower.startsWith("q:")
                    || lower.contains("how to get") || lower.contains("write a function")
                    || tag.chars().anyMatch(codePoint -> codePoint > 127 && Character.isLetter(codePoint))) continue;
            target.add(lower);
        }
    }

    private boolean forbiddenTag(String value) {
        return value.toLowerCase(Locale.ROOT).matches("(?:score_[0-9]+(?:_up)?|rating_.+|source_.+)");
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
