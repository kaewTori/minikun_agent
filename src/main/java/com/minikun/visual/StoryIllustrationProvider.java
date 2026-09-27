package com.minikun.visual;

/** Generates one image from a privacy-bounded story scene prompt. */
public interface StoryIllustrationProvider {
    GeneratedImage generate(String prompt);

    default String effectiveNegativePrompt(String negativePrompt) {
        return negativePrompt == null ? "" : negativePrompt.strip();
    }

    default GeneratedImage generate(ImageGenerationRequest request) {
        return generate(request.prompt());
    }
}
