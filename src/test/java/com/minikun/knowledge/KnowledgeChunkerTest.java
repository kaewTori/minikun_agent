package com.minikun.knowledge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KnowledgeChunkerTest {
    @Test
    void createsBoundedHeadingAwareChunks() {
        String content = "# Architecture\n" + "Minikun knowledge content ".repeat(80);
        var chunks = new KnowledgeChunker(300, 40).chunk(content);
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.content().length() <= 300));
        assertTrue(chunks.stream().allMatch(chunk -> "Architecture".equals(chunk.heading())));
        assertFalse(chunks.stream().anyMatch(chunk -> chunk.hash().isBlank()));
    }
}
