package com.minikun.visual;

import java.util.List;

/** Stable, local visual identity reused for one character within a conversation. */
public record CharacterVisualProfile(
        String name,
        String identity,
        List<String> appearance,
        List<String> clothing,
        List<String> accessories,
        List<String> canonicalTags,
        List<String> negativeTags) {

    private static final int MAX_LIST_ITEMS = 16;

    public CharacterVisualProfile {
        name = text(name, 120);
        identity = text(identity, 160);
        appearance = tags(appearance);
        clothing = tags(clothing);
        accessories = tags(accessories);
        canonicalTags = tags(canonicalTags);
        negativeTags = tags(negativeTags);
        if (name.isBlank()) throw new IllegalArgumentException("character name is required");
        if (identity.isBlank() && appearance.isEmpty() && canonicalTags.isEmpty()) {
            throw new IllegalArgumentException("character visual identity is required");
        }
    }

    /** Existing identity wins so later episodes cannot silently redesign the character. */
    public CharacterVisualProfile lockWith(CharacterVisualProfile update) {
        if (update == null || !key().equals(update.key())) return this;
        return new CharacterVisualProfile(
                name,
                identity.isBlank() ? update.identity() : identity,
                appearance.isEmpty() ? update.appearance() : appearance,
                clothing.isEmpty() ? update.clothing() : clothing,
                accessories.isEmpty() ? update.accessories() : accessories,
                canonicalTags.isEmpty() ? update.canonicalTags() : canonicalTags,
                merge(negativeTags, update.negativeTags));
    }

    public String key() {
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static List<String> merge(List<String> first, List<String> second) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(first);
        merged.addAll(second);
        return merged.stream().limit(MAX_LIST_ITEMS).toList();
    }

    private static List<String> tags(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(value -> text(value, 160))
                .filter(value -> !value.isBlank())
                .filter(value -> !value.equalsIgnoreCase("source_anime"))
                .distinct().limit(MAX_LIST_ITEMS).toList();
    }

    private static String text(String value, int maximum) {
        String result = value == null ? "" : value.replaceAll("[\\r\\n\\p{Cntrl}]+", " ")
                .replaceAll("\\s+", " ").strip();
        return result.length() <= maximum ? result : result.substring(0, maximum).stripTrailing();
    }
}
