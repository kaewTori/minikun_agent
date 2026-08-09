package com.minikun.pcs.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class KnowledgeContextTest {
    @Test
    void existingTextOnlyConstructionUsesEmptyImages() {
        KnowledgeContext context = new KnowledgeContext("text");

        assertEquals("text", context.content());
        assertEquals(List.of(), context.images());
        assertNotNull(context.images());
    }

    @Test
    void preservesImagesWithoutExposingMutableCollection() {
        List<ImageSource> images = new ArrayList<>();
        ImageSource image = new ImageSource("image-url", "title", "source-url", "description");
        images.add(image);

        KnowledgeContext context = new KnowledgeContext("text", List.of(), images);
        images.clear();

        assertEquals(List.of(image), context.images());
        assertThrows(UnsupportedOperationException.class, () -> context.images().clear());
    }

    @Test
    void nullImagesBecomeEmptyCollection() {
        KnowledgeContext context = new KnowledgeContext("text", List.of(), null);

        assertEquals(List.of(), context.images());
        assertNotNull(context.images());
    }

    @Test
    void legacyJsonWithoutImagesUsesEmptyCollection() throws Exception {
        KnowledgeContext context = new ObjectMapper().readValue(
                "{\"content\":\"legacy\"}", KnowledgeContext.class);

        assertEquals("legacy", context.content());
        assertEquals(List.of(), context.images());
    }
}