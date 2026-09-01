package com.minikun.visual;

/** Ownership and story context attached to generated-image history. */
public record ImageGenerationScope(
        String ownerId,
        String conversationId,
        String origin,
        String illustrationMode,
        String sceneTitle) {

    public ImageGenerationScope {
        ownerId = required(ownerId, "default");
        conversationId = required(conversationId, "standalone");
        origin = required(origin, "generated");
        illustrationMode = optional(illustrationMode);
        sceneTitle = optional(sceneTitle);
    }

    public static ImageGenerationScope standalone() {
        return new ImageGenerationScope("default", "standalone", "generated", "", "");
    }

    private static String required(String value, String fallback) {
        String result = optional(value);
        return result.isBlank() ? fallback : result;
    }

    private static String optional(String value) {
        return value == null ? "" : value.strip();
    }
}
