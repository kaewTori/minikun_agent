package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ImageAwarenessTest {
    @Test
    void storesSelectedImageCount() {
        ImageAwareness awareness = new ImageAwareness(3);

        assertEquals(3, awareness.count());
        org.junit.jupiter.api.Assertions.assertTrue(awareness.hasImages());
    }

    @Test
    void zeroCountRepresentsNoImages() {
        ImageAwareness awareness = new ImageAwareness(0);

        assertEquals(0, awareness.count());
        org.junit.jupiter.api.Assertions.assertFalse(awareness.hasImages());
    }

    @Test
    void rejectsNegativeCounts() {
        assertThrows(IllegalArgumentException.class, () -> new ImageAwareness(-1));
    }
}
