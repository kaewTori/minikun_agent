package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoryIllustrationServiceTest {
    @TempDir Path directory;

    @Test
    void buildsAScenePromptAndReturnsGeneratedAttachment() {
        AtomicReference<String> prompt = new AtomicReference<>();
        StoryIllustrationService service = new StoryIllustrationService(value -> {
            prompt.set(value);
            return new GeneratedImage(GeneratedImageStoreTest.png(), "คืนที่ดาวเต็มฟ้า", "test-image-model");
        }, new GeneratedImageStore(directory, 1024, Clock.systemUTC()), true, 2000);

        var attachments = service.illustrate(
                "แต่งเรื่องสั้นเกี่ยวกับแมวที่ตามหาดวงดาว",
                "ในคืนที่เงียบงัน มะลิปีนขึ้นไปถึงหลังคาหอดูดาวและพบแสงที่ตามหา");

        assertEquals(1, attachments.size());
        assertEquals("generated", attachments.getFirst().origin());
        assertEquals("test-image-model", attachments.getFirst().provider());
        assertTrue(attachments.getFirst().url().startsWith("/v1/images/generated/"));
        assertTrue(prompt.get().replaceAll("\\s+", " ")
                .contains("single most emotionally decisive visual moment"));
        assertTrue(prompt.get().contains("มะลิปีนขึ้นไปถึงหลังคาหอดูดาว"));
    }

    @Test
    void failsOpenWhenProviderCannotGenerate() {
        StoryIllustrationService service = new StoryIllustrationService(value -> {
            throw new ImageGenerationException("provider unavailable");
        }, new GeneratedImageStore(directory, 1024, Clock.systemUTC()), true, 1000);

        assertTrue(service.illustrate("แต่งนิทานให้หน่อย", "กาลครั้งหนึ่ง").isEmpty());
        assertFalse(service.shouldIllustrate("วันนี้อากาศเป็นอย่างไร"));
    }
}
