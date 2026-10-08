package com.minikun.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class EmbeddingSupportTest {
    @Test
    void formatsRetrievalAndRejectsInvalidVectorsWithoutChangingLegacyInput() {
        String model = "embeddinggemma-2:270m-mxfp8-text";
        assertEquals("task: search result | query: แมว", EmbeddingSupport.query(model, "แมว", "old instruction"));
        assertEquals("title: PROFILE | text: แมวชื่อมะลิ", EmbeddingSupport.document(model, "PROFILE", "แมวชื่อมะลิ"));
        assertEquals("title: none | text: ข้อมูล", EmbeddingSupport.document(model, "", "ข้อมูล"));
        assertEquals("แมว", EmbeddingSupport.query("qwen3-embedding:0.6b", "แมว", ""));
        assertEquals("Instruct: retrieve\nQuery: แมว", EmbeddingSupport.query("qwen3-embedding:0.6b", "แมว", "retrieve"));
        assertEquals("ข้อมูล", EmbeddingSupport.document("qwen3-embedding:0.6b", "title", "ข้อมูล"));
        assertArrayEquals(new float[] {1, 0}, EmbeddingSupport.requireVector(new float[] {1, 0}));
        for (float[] invalid : new float[][] {null, {}, {0, 0}, {Float.NaN, 1}, {Float.POSITIVE_INFINITY}})
            assertThrows(IllegalArgumentException.class, () -> EmbeddingSupport.requireVector(invalid));
    }
}
