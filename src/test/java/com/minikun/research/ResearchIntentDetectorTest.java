package com.minikun.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResearchIntentDetectorTest {
    private final ResearchIntentDetector detector = new ResearchIntentDetector();

    @Test
    void detectsThaiDeepResearchAndAnalysis() {
        ResearchIntent intent = detector.detect("ช่วยค้นคว้าแบบเจาะลึกและวิเคราะห์ผลกระทบของเรื่องนี้");

        assertTrue(intent.deepResearch());
        assertTrue(intent.storytellingRequested());
        assertEquals(NarrativeMode.ANALYSIS, intent.narrativeMode());
    }

    @Test
    void explicitStoryTakesPriorityOverOtherStructures() {
        ResearchIntent intent = detector.detect("เล่าประวัติของอินเทอร์เน็ตให้เป็นเรื่องราวที่น่าติดตาม");

        assertFalse(intent.deepResearch());
        assertEquals(NarrativeMode.STORY, intent.narrativeMode());
    }

    @Test
    void ordinaryConversationStaysOnTheDirectFastPath() {
        ResearchIntent intent = detector.detect("วันนี้เหนื่อยนิดหน่อย");

        assertFalse(intent.deepResearch());
        assertFalse(intent.storytellingRequested());
        assertEquals(NarrativeMode.DIRECT, intent.narrativeMode());
    }
}
