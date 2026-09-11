package com.minikun.model;

import java.util.Locale;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/** Configure exact aliases through a JVM property; unknown models keep thinking disabled. */
public final class OllamaReasoning {
    private OllamaReasoning() { }

    /** Qwen3 variants use the same /no_think control, including Hub/GGUF names. */
    public static boolean qwen3Family(String model) {
        return model != null && model.toLowerCase(Locale.ROOT).contains("qwen3");
    }

    public static String selectedModel(String mainModel, GenerationOptions.Reasoning effort) {
        effort = effort == null ? GenerationOptions.Reasoning.OFF : effort;
        String reasoningModel = System.getProperty("minikun.reasoning.model",
                System.getenv().getOrDefault("MINIKUN_REASONING_MODEL", "")).strip();
        return effort != GenerationOptions.Reasoning.OFF && !reasoningModel.isEmpty() ? reasoningModel : mainModel;
    }
    public static int contextSize() {
        int size = Integer.parseInt(System.getProperty("minikun.reasoning.context-size",
                System.getenv().getOrDefault("MINIKUN_REASONING_CONTEXT_SIZE", "8192")));
        if (size < 1024) throw new IllegalArgumentException("reasoning context must be at least 1024 tokens");
        return size;
    }
    public static boolean supported(String model) {
        String name = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return qwen3Family(name)
                || java.util.Set.of("deepseek-r1", "deepseek-v3.1", "gpt-oss").contains(name.split(":")[0])
                || java.util.Arrays.stream(System.getProperty("minikun.reasoning.models", System.getenv().getOrDefault("MINIKUN_REASONING_MODELS", "")).split(","))
                    .anyMatch(alias -> !alias.isBlank() && alias.strip().equalsIgnoreCase(name));
    }
    public static Object wire(String model, GenerationOptions.Reasoning effort) {
        effort = effort == null ? GenerationOptions.Reasoning.OFF : effort;
        if (!supported(model)) return false;
        if (model.toLowerCase(Locale.ROOT).split(":")[0].equals("gpt-oss")) {
            return switch (effort) { case HIGH -> "high"; case MEDIUM, AUTO -> "medium"; default -> "low"; };
        }
        return effort != GenerationOptions.Reasoning.OFF;
    }
    public static void apply(OllamaChatOptions.Builder builder, String model, GenerationOptions.Reasoning effort) {
        Object wire = wire(model, effort);
        if (wire instanceof Boolean enabled) {
            if (enabled) builder.enableThinking(); else builder.disableThinking();
        } else switch (wire.toString()) {
            case "high" -> builder.thinkHigh();
            case "medium" -> builder.thinkMedium();
            default -> builder.thinkLow();
        }
    }
}
