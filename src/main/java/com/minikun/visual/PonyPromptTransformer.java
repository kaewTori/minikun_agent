package com.minikun.visual;

import java.util.List;
import java.util.Map;

/** Converts a conversational image brief into an ordered Pony XL prompt. */
@FunctionalInterface
public interface PonyPromptTransformer {
    String transform(String brief);

    default Result transformWithCharacters(String brief) {
        return new Result(transform(brief), List.of());
    }

    default Grouped transformGrouped(String brief) {
        String prompt = transform(brief);
        return new Grouped(prompt, Map.of("character", prompt));
    }

    record Grouped(String prompt, Map<String, String> groups) {
        public Grouped {
            prompt = prompt == null ? "" : prompt.strip();
            groups = groups == null ? Map.of() : Map.copyOf(groups);
        }
    }

    record Result(String prompt, List<String> facePrompts) {
        public Result {
            prompt = prompt == null ? "" : prompt.strip();
            facePrompts = facePrompts == null ? List.of() : List.copyOf(facePrompts);
        }
    }
}
