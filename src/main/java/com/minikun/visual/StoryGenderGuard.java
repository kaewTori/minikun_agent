package com.minikun.visual;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Rejects human genders that contradict the supplied story. */
final class StoryGenderGuard {
    private static final Pattern FEMALE_TERM = Pattern.compile(
            "(?<![a-z0-9])(?:(?:\\d+\\s*)?girls?|women|woman|females?|she|her)(?![a-z0-9])"
                    + "|(?:ผู้หญิง|เด็กหญิง|หญิงสาว|เด็กสาว|สตรี|เธอ)");
    private static final Pattern MALE_TERM = Pattern.compile(
            "(?<![a-z0-9])(?:(?:\\d+\\s*)?boys?|men|man|males?|he|him|his)(?![a-z0-9])"
                    + "|(?:ผู้ชาย|เด็กชาย|ชายหนุ่ม|เด็กหนุ่ม|บุรุษ)");
    private static final Pattern FEMALE_EXCLUSION = Pattern.compile(
            "(?<![a-z0-9])(?:no|without)\\s+(?:girls?|women|woman|females?)(?![a-z0-9])"
                    + "|(?:ไม่มี|ห้ามมี|ไร้)\\s*(?:ผู้หญิง|เด็กหญิง|หญิงสาว|เด็กสาว|สตรี)");
    private static final Pattern MALE_EXCLUSION = Pattern.compile(
            "(?<![a-z0-9])(?:no|without)\\s+(?:boys?|men|man|males?)(?![a-z0-9])"
                    + "|(?:ไม่มี|ห้ามมี|ไร้)\\s*(?:ผู้ชาย|เด็กชาย|ชายหนุ่ม|เด็กหนุ่ม|บุรุษ)");

    private StoryGenderGuard() {}

    static PonyPromptTransformer.Result correct(String source, PonyPromptTransformer.Result candidate) {
        if (candidate == null) return null;
        String facts = source == null ? "" : source.toLowerCase(Locale.ROOT);
        boolean sourceHasFemale = FEMALE_TERM.matcher(facts).find();
        boolean sourceHasMale = MALE_TERM.matcher(facts).find();
        boolean femaleAllowed = sourceHasFemale && !FEMALE_EXCLUSION.matcher(facts).find();
        boolean maleAllowed = sourceHasMale && !MALE_EXCLUSION.matcher(facts).find();
        boolean femaleOnly = femaleAllowed
                && (MALE_EXCLUSION.matcher(facts).find() || !sourceHasMale);
        boolean maleOnly = maleAllowed
                && (FEMALE_EXCLUSION.matcher(facts).find() || !sourceHasFemale);
        if (!femaleOnly && !maleOnly) return candidate;
        java.util.function.UnaryOperator<String> fix = value ->
                value == null ? "" : replaceGender(value, femaleOnly);
        java.util.function.UnaryOperator<String> fixAndAnchor = value -> {
            String result = fix.apply(value);
            String required = femaleOnly ? "1girl" : "1boy";
            return hasGender(result, femaleOnly) ? result : required + ", " + result;
        };
        return new PonyPromptTransformer.Result(
                fixAndAnchor.apply(candidate.prompt()),
                candidate.facePrompts().stream().map(fixAndAnchor).toList());
    }

    static void validate(String source, String candidate, List<CharacterVisualProfile> remembered) {
        String facts = source == null ? "" : source.toLowerCase(Locale.ROOT);
        String prompt = candidate == null ? "" : candidate.toLowerCase(Locale.ROOT);
        List<CharacterVisualProfile> memory = remembered == null ? List.of() : remembered;
        boolean sourceHasFemale = FEMALE_TERM.matcher(facts).find();
        boolean sourceHasMale = MALE_TERM.matcher(facts).find();
        boolean femaleAllowed = sourceHasFemale && !FEMALE_EXCLUSION.matcher(facts).find();
        boolean maleAllowed = sourceHasMale && !MALE_EXCLUSION.matcher(facts).find();
        boolean rememberedFemale = memory.stream().anyMatch(character ->
                FEMALE_TERM.matcher(character.identity().toLowerCase(Locale.ROOT)).find());
        boolean rememberedMale = memory.stream().anyMatch(character ->
                MALE_TERM.matcher(character.identity().toLowerCase(Locale.ROOT)).find());
        if (MALE_TERM.matcher(prompt).find() && (MALE_EXCLUSION.matcher(facts).find()
                || femaleAllowed && !sourceHasMale && !rememberedMale)) {
            throw new IllegalArgumentException("visual plan invented a male character");
        }
        if (FEMALE_TERM.matcher(prompt).find() && (FEMALE_EXCLUSION.matcher(facts).find()
                || maleAllowed && !sourceHasFemale && !rememberedFemale)) {
            throw new IllegalArgumentException("visual plan invented a female character");
        }
    }

    private static String replaceGender(String value, boolean female) {
        String result = value;
        String[][] replacements = female
                ? new String[][] {{"boys", "girls"}, {"boy", "girl"}, {"men", "women"},
                        {"man", "woman"}, {"male", "female"}}
                : new String[][] {{"girls", "boys"}, {"girl", "boy"}, {"women", "men"},
                        {"woman", "man"}, {"female", "male"}};
        for (String[] replacement : replacements) {
            result = result.replaceAll(
                    "(?i)(?<![a-z])" + Pattern.quote(replacement[0]) + "(?![a-z])",
                    replacement[1]);
        }
        return result;
    }

    private static boolean hasGender(String value, boolean female) {
        return (female ? FEMALE_TERM : MALE_TERM).matcher(value.toLowerCase(Locale.ROOT)).find();
    }
}
