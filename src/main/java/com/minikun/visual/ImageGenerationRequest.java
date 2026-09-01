package com.minikun.visual;

import java.util.List;

/** Optional generation controls shared by the Image Studio and concrete image providers. */
public record ImageGenerationRequest(
        String prompt,
        String negativePrompt,
        List<String> facePrompts,
        Integer width,
        Integer height,
        Integer steps,
        Double guidance,
        String scheduler,
        String schedule,
        Long seed) {
    public ImageGenerationRequest {
        prompt = prompt == null ? "" : prompt.strip();
        negativePrompt = negativePrompt == null ? "" : negativePrompt.strip();
        facePrompts = facePrompts == null ? List.of() : List.copyOf(facePrompts);
        scheduler = scheduler == null ? "" : scheduler.strip();
        schedule = schedule == null ? "" : schedule.strip();
    }

    public static ImageGenerationRequest promptOnly(String prompt) {
        return new ImageGenerationRequest(prompt, "", List.of(), null, null, null, null, "", "", null);
    }
}
