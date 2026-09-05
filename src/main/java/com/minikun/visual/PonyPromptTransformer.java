package com.minikun.visual;

import java.util.List;

/** Converts a conversational image brief into an ordered Pony XL prompt. */
@FunctionalInterface
public interface PonyPromptTransformer {
    String transform(String brief);

    default Result transformWithCharacters(String brief) {
        return new Result(transform(brief), List.of());
    }

    record Result(String prompt, List<String> facePrompts) {
        public Result {
            prompt = prompt == null ? "" : prompt.strip();
            facePrompts = facePrompts == null ? List.of() : List.copyOf(facePrompts);
        }
    }
}
