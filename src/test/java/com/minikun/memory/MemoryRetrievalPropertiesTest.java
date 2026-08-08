package com.minikun.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MemoryRetrievalPropertiesTest {
    @Test
    void acceptsZeroAndPositiveBounds() {
        assertEquals(0, new MemoryRetrievalProperties(0).maxCandidates());
        assertEquals(20, new MemoryRetrievalProperties(20).maxCandidates());
    }

    @Test
    void rejectsNegativeBounds() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryRetrievalProperties(-1));
    }
}
