package com.minikun.knowledge;

final class KnowledgeEmbeddingCodec {
    private KnowledgeEmbeddingCodec() {}

    static String encode(float[] vector) {
        if (vector == null || vector.length == 0) return "";
        StringBuilder result = new StringBuilder(vector.length * 10);
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) result.append(',');
            result.append(Float.toString(vector[index]));
        }
        return result.toString();
    }

    static float[] decode(String value) {
        if (value == null || value.isBlank()) return new float[0];
        String[] fields = value.split(",");
        float[] result = new float[fields.length];
        for (int index = 0; index < fields.length; index++) result[index] = Float.parseFloat(fields[index]);
        return result;
    }
}
