package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class GeneratedImageStoreTest {
    @TempDir Path directory;

    @Test
    void storesAndReadsAnOpaqueLocalPng() {
        GeneratedImageStore store = new GeneratedImageStore(directory, 1024, Clock.systemUTC());
        byte[] png = png();

        GeneratedImageStore.StoredImage stored = store.save(png);
        GeneratedImageStore.StoredImageContent content = store.read(stored.filename());

        assertEquals("image/png", stored.contentType());
        assertEquals("image/png", content.contentType());
        assertArrayEquals(png, content.bytes());
        assertEquals("/v1/images/generated/" + stored.filename(), stored.url());
    }

    @Test
    void rejectsUnknownFormatsAndTraversalNames() {
        GeneratedImageStore store = new GeneratedImageStore(directory, 1024, Clock.systemUTC());

        assertThrows(ImageGenerationException.class, () -> store.save(new byte[] {1, 2, 3}));
        assertThrows(ImageGenerationException.class, () -> store.read("../secret.png"));
    }

    static byte[] png() {
        return new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
    }
}
