package com.minikun.visual;

/** Converts a story into a short English prompt that fits one SDXL CLIP chunk. */
public interface StoryVisualPromptGenerator {
    String generate(String userMessage, String assistantStory);

    static String fallback(String userMessage, String assistantStory) {
        String source = (userMessage == null ? "" : userMessage) + " "
                + (assistantStory == null ? "" : assistantStory);
        java.util.ArrayList<String> details = new java.util.ArrayList<>();
        if (contains(source, "แมว", "cat")) details.add("a black cat");
        if (contains(source, "หอดูดาว", "observatory")) details.add("inside an old observatory");
        if (contains(source, "ดาว", "star")) details.add("glowing starlight");
        if (details.isEmpty()) details.add("the story's decisive emotional moment");
        details.add("warm cinematic lighting");
        details.add("clear subject and detailed background");
        return String.join(", ", details);
    }

    private static boolean contains(String source, String thai, String english) {
        return source.contains(thai) || source.toLowerCase(java.util.Locale.ROOT).contains(english);
    }
}
