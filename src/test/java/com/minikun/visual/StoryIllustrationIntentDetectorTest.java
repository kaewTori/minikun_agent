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

    @Test
    void recognizesTheReportedDiagramAndInfoRequestsWithoutMatchingOrdinaryInformation() {
        assertEquals(StoryIllustrationIntent.VECTOR_GRAPHIC,
                detector.detect("มินิคุง ช่วยทำแผนภาพเกี่ยวกับตัวมินิคุงมาให้เราหน่อย", false));
        assertEquals(StoryIllustrationIntent.VECTOR_GRAPHIC,
                detector.detect("อยากได้วิธีคิดของมินิคุง ในรูปแบบ info", false));
        assertEquals(StoryIllustrationIntent.NONE, detector.detect("ขอ info เกี่ยวกับ PostgreSQL", false));
        assertEquals(StoryIllustrationIntent.NONE, detector.detect("อย่าทำแผนภาพ ขอข้อความ", false));
    }

    @Test
    void recognizesGraphicRevisionsAndIgnoresOldInstructionsInTheirContext() {
        for (String message : java.util.List.of("ปรับให้ลองทำเป็น flowchart", "เปลี่ยนเป็น flowchart",
                "แก้เป็นแผนผัง", "convert it to a flowchart")) {
            assertEquals(StoryIllustrationIntent.VECTOR_GRAPHIC, detector.detect(message, false));
        }
        assertEquals(StoryIllustrationIntent.NONE, detector.detect("ไม่ต้องเปลี่ยนเป็น flowchart", false));
        assertEquals(StoryIllustrationIntent.NONE, detector.detect("อธิบายว่า flowchart คืออะไร", false));
        assertEquals(StoryIllustrationIntent.VECTOR_GRAPHIC, detector.detect("เปลี่ยนเป็น flowchart"
                + StoryIllustrationIntentDetector.PREVIOUS_GRAPHIC_CONTEXT
                + "assistant: ไม่ต้องสร้างภาพ เป็นแค่ข้อความก่อน", false));
    }

    @Test
    void routesRequestedDiagramsToVectorUnlessRasterWasRequested() {
        assertEquals(StoryIllustrationIntent.VECTOR_GRAPHIC,
                detector.detect("ช่วยทำอินโฟกราฟิกเรื่องการประหยัดไฟ", false));
        assertEquals(StoryIllustrationIntent.VECTOR_GRAPHIC,
                detector.detect("วาด flowchart ขั้นตอนสมัครสมาชิก", false));
        assertEquals(StoryIllustrationIntent.NONE,
                detector.detect("อธิบายกราฟนี้ให้ฟัง", false));
        assertEquals(StoryIllustrationIntent.NONE,
                detector.detect("เล่าเรื่องให้ฟัง ไม่ต้องสร้างภาพ", true));
        assertEquals(StoryIllustrationIntent.NONE,
                detector.detect("ไม่เอาภาพประกอบ ขอแค่ข้อความ", true));
        assertEquals(StoryIllustrationIntent.VECTOR_GRAPHIC,
                detector.detect("ทำอินโฟกราฟิก ไม่เอารูปคน", false));
        assertEquals(StoryIllustrationIntent.DIRECT_IMAGE,
                detector.detect("สร้างภาพอินโฟกราฟิกเป็น PNG", false));
    }
}
