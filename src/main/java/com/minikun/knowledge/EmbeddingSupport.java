package com.minikun.knowledge;

/** Model-specific retrieval text; the public embedding API accepts already-formatted input. */
public final class EmbeddingSupport {
    private EmbeddingSupport() {}

    public static boolean gemma(String model) {
        return model != null && model.startsWith("embeddinggemma-2:");
    }

    public static String query(String model, String text, String legacyInstruction) {
        if (gemma(model)) return "task: search result | query: " + text;
        return legacyInstruction.isBlank() ? text : "Instruct: " + legacyInstruction + "\nQuery: " + text;
    }

    public static String document(String model, String title, String text) {
        if (!gemma(model)) return text;
        return "title: " + (title == null || title.isBlank() ? "none" : title.strip()) + " | text: " + text;
    }

    public static float[] requireVector(float[] vector) {
        if (vector == null || vector.length == 0) throw new IllegalArgumentException("embedding vector is empty");
        boolean nonzero = false;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("embedding vector must be finite");
            nonzero |= value != 0;
        }
        if (!nonzero) throw new IllegalArgumentException("embedding vector must not be zero");
        return vector;
    }
}
