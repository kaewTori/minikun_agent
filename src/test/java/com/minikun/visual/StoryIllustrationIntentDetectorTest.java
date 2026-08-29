package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StoryIllustrationIntentDetectorTest {
    private final StoryIllustrationIntentDetector detector = new StoryIllustrationIntentDetector();

    @Test
    void detectsDirectImageCreationWithoutConfusingImageSearch() {
        assertEquals(StoryIllustrationIntent.DIRECT_IMAGE,
                detector.detect("ช่วยสร้างภาพเมืองลอยฟ้ายามค่ำคืน", false));
        assertEquals(StoryIllustrationIntent.NONE,
                detector.detect("ช่วยหารูปเมืองลอยฟ้าจากเว็บ", true));
    }

    @Test
    void detectsExplicitAndAutomaticStoryIllustrations() {
        assertEquals(StoryIllustrationIntent.EXPLICIT_STORY_ILLUSTRATION,
                detector.detect("เล่านิทานเรื่องแมวหลงทางพร้อมภาพประกอบ", false));
        assertEquals(StoryIllustrationIntent.AUTOMATIC_CREATIVE_STORY,
                detector.detect("แต่งเรื่องสั้นเกี่ยวกับหุ่นยนต์ที่กลัวฝน", true));
    }

    @Test
    void factualNarrativeDoesNotSpendOnAnImageUnlessExplicitlyRequested() {
        assertEquals(StoryIllustrationIntent.NONE,
                detector.detect("เล่าประวัติอินเทอร์เน็ตให้ฟัง", true));
    }
}
