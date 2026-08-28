package com.minikun.personality.learning;

import java.util.Map;
import java.util.Set;

/** Closed vocabulary prevents arbitrary text from entering adaptive prompt instructions. */
public final class AdaptationDimensions {
    public static final String PREFIX = "adaptive.";
    public static final String LANGUAGE = "language";
    public static final String RESPONSE_LENGTH = "response_length";
    public static final String RESPONSE_FORMAT = "response_format";
    public static final String EXPLANATION_LEVEL = "explanation_level";
    public static final String TONE = "tone";
    public static final String QUESTION_FREQUENCY = "question_frequency";
    public static final String INITIATIVE = "initiative";
    public static final String CHALLENGE = "challenge";

    private static final Map<String, Set<String>> VALUES = Map.of(
            LANGUAGE, Set.of("th", "en"),
            RESPONSE_LENGTH, Set.of("concise", "detailed"),
            RESPONSE_FORMAT, Set.of("bullets", "steps", "prose"),
            EXPLANATION_LEVEL, Set.of("simple", "technical"),
            TONE, Set.of("casual", "professional"),
            QUESTION_FREQUENCY, Set.of("minimal", "balanced"),
            INITIATIVE, Set.of("low", "balanced", "high"),
            CHALLENGE, Set.of("gentle", "balanced", "direct"));

    private AdaptationDimensions() {}

    public static boolean supported(String dimension, String value) {
        return dimension != null && value != null
                && VALUES.getOrDefault(dimension.trim(), Set.of()).contains(value.trim());
    }

    public static Set<String> dimensions() {
        return VALUES.keySet();
    }

    public static String preferenceKey(String dimension) {
        if (!VALUES.containsKey(dimension)) {
            throw new IllegalArgumentException("unsupported adaptation dimension");
        }
        return PREFIX + dimension;
    }
}
