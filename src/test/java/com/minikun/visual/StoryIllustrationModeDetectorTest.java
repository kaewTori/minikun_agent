package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StoryIllustrationModeDetectorTest {
    private final StoryIllustrationModeDetector detector = new StoryIllustrationModeDetector();

    @Test
    void selectsExplicitVisualTreatmentsAndDefaultsToDecisiveScene() {
        assertEquals(StoryIllustrationMode.STORYBOARD,
                detector.detect("แต่งเรื่องพร้อมภาพแต่ละฉากแบบ storyboard"));
        assertEquals(StoryIllustrationMode.COVER, detector.detect("ช่วยทำภาพปกนิทานเรื่องนี้"));
        assertEquals(StoryIllustrationMode.CHARACTER_PORTRAIT,
                detector.detect("ขอ character portrait ของตัวเอก"));
        assertEquals(StoryIllustrationMode.ENDING_SCENE,
                detector.detect("แต่งเรื่องแล้วสร้างภาพฉากจบให้ด้วย"));
        assertEquals(StoryIllustrationMode.DECISIVE_SCENE,
                detector.detect("แต่งเรื่องสั้นเกี่ยวกับแมว"));
    }
}
