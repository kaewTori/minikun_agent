package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolRuntimeIntentDetectorTest {
    private final ToolRuntimeIntentDetector detector = new ToolRuntimeIntentDetector();

    @Test
    void keepsQuestionsAndTechnicalWorkOnDirectGeneration() {
        assertFalse(detector.requiresTools("อธิบาย performance ของ JVM ให้หน่อย"));
        assertFalse(detector.requiresTools("ช่วยวิเคราะห์ architecture นี้"));
        assertFalse(detector.requiresTools("ค้นข้อมูล Java รุ่นล่าสุด"));
    }

    @Test
    void routesExplicitExternalActionsToTools() {
        assertTrue(detector.requiresTools("สร้าง task เตือนให้โทรหาแม่พรุ่งนี้"));
        assertTrue(detector.requiresTools("ลบไฟล์รายงานเก่าในคอมพิวเตอร์"));
        assertTrue(detector.requiresTools("ใช้ tool ตรวจระบบให้หน่อย"));
        assertTrue(detector.requiresTools("เปิดเว็บไซต์นี้ให้หน่อย"));
        assertTrue(detector.requiresTools("เช็กสถานะ service ให้หน่อย"));
    }
}
