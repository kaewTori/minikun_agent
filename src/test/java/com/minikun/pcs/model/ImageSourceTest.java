package com.minikun.pcs.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ImageSourceTest {
    @Test
    void preservesFieldsAndUsesRecordEquality() {
        ImageSource image = new ImageSource(
                "https://example.com/image.jpg",
                "Example image",
                "https://example.com/article",
                "An example image");

        assertEquals("https://example.com/image.jpg", image.url());
        assertEquals("Example image", image.title());
        assertEquals("https://example.com/article", image.sourceUrl());
        assertEquals("An example image", image.description());
        assertEquals(image, new ImageSource(
                "https://example.com/image.jpg",
                "Example image",
                "https://example.com/article",
                "An example image"));
    }
}