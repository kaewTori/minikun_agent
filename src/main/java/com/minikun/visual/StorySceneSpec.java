package com.minikun.visual;

import java.util.List;

/** Validated visual facts for exactly one generated frame. */
public record StorySceneSpec(
        String title,
        int subjectCount,
        List<String> characterNames,
        String action,
        String interaction,
        List<String> keyObjects,
        String setting,
        String time,
        String weather,
        String emotion,
        String atmosphere,
        String lighting,
        String palette,
        String composition,
        String cameraAngle,
        String shotDistance,
        String focus,
        List<String> mustInclude,
        List<String> mustNotInclude,
        List<String> fineDetails) {

    private static final int MAX_LIST_ITEMS = 20;

    public StorySceneSpec {
        title = text(title, 120);
        characterNames = tags(characterNames);
        action = text(action, 240);
        interaction = text(interaction, 240);
        keyObjects = tags(keyObjects);
        setting = text(setting, 240);
        time = text(time, 120);
        weather = text(weather, 120);
        emotion = text(emotion, 160);
        atmosphere = text(atmosphere, 160);
        lighting = text(lighting, 160);
        palette = text(palette, 160);
        composition = text(composition, 160);
        cameraAngle = text(cameraAngle, 120);
        shotDistance = text(shotDistance, 120);
        focus = text(focus, 160);
        mustInclude = tags(mustInclude);
        mustNotInclude = tags(mustNotInclude);
        fineDetails = tags(fineDetails);
        if (subjectCount < 0 || subjectCount > 8) {
            throw new IllegalArgumentException("scene subject count must be between 0 and 8");
        }
        if (setting.isBlank() && mustInclude.isEmpty() && action.isBlank() && keyObjects.isEmpty()) {
            throw new IllegalArgumentException("scene requires a visible action, object, setting, or anchor");
        }
    }

    private static List<String> tags(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(value -> text(value, 180))
                .filter(value -> !value.isBlank())
                .filter(value -> !value.equalsIgnoreCase("none") && !value.equalsIgnoreCase("unknown"))
                .filter(value -> !value.equalsIgnoreCase("source_anime"))
                .distinct().limit(MAX_LIST_ITEMS).toList();
    }

    private static String text(String value, int maximum) {
        String result = value == null ? "" : value.replaceAll("[\\r\\n\\p{Cntrl}]+", " ")
                .replaceAll("\\s+", " ").strip();
        if (result.equalsIgnoreCase("none") || result.equalsIgnoreCase("unknown")
                || result.equalsIgnoreCase("source_anime")) return "";
        return result.length() <= maximum ? result : result.substring(0, maximum).stripTrailing();
    }
}
