package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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

    @Test
    void routesPowerPointDeliverablesButNotSlidePlanning() {
        assertTrue(detector.requiresTools("ช่วยทำสไลด์แบบสวย ๆ ให้หน่อย"));
        assertTrue(detector.requiresTools("ทำเป็นสไลด์ให้เราหน่อยนะ"));
        assertTrue(detector.requiresTools("ช่วยทำ PowerPoint 8 หน้าให้หน่อย"));
        assertFalse(detector.requiresTools("ช่วยวางแผนให้มินิคุงทำสไลด์ได้"));
    }

    @Test
    void routesNaturalInvestmentReviewsToTools() {
        assertTrue(detector.requiresTools("ช่วยวิเคราะห์พอร์ตระยะยาวของฉัน"));
        assertTrue(detector.requiresTools("ช่วยดู portfolio ที่ถืออยู่"));
        assertTrue(detector.requiresTools("ช่วยบันทึก thesis ของ AMZN"));
        assertFalse(detector.requiresTools("ช่วยวิเคราะห์หุ้น AMZN ให้หน่อย"));
    }

    @Test
    void keepsStockMarketAnalysisOnTheResearchRoute() {
        assertFalse(detector.requiresTools("ช่วยวิเคราะห์ตลาดหุ้น"));
        assertFalse(detector.requiresTools("ช่วยวิเคราะห์ตลาด"));
        assertFalse(detector.requiresTools("analyze the stock market"));
        assertFalse(detector.requiresTools("market analysis"));
        assertTrue(detector.requiresTools("สรุปข่าวตลาดหุ้นวันนี้"));
    }

    @Test
    void routesNaturalInvestmentPriceQuestionsToTools() {
        assertTrue(detector.requiresTools("ดูราคาปัจจุบันของ AMZN"));
        assertTrue(detector.requiresTools("ช่วยประเมินมูลค่าพอร์ตจากราคาตลาด"));
    }

    @Test
    void routesPlainPortfolioInventoryQuestionsToTools() {
        List.of(
                "พอร์ตเรามีอะไรบ้าง",
                "port เรามีหุ้นอะไรบ้าง",
                "what stocks do I hold",
                "ช่วยดูพอร์ตของเรา",
                "มีอะไรอยู่ใน port",
                "ตอนนี้ฉันถืออะไรอยู่",
                "แสดงรายการลงทุนของฉัน",
                "what's in my portfolio",
                "list my positions").forEach(text -> assertTrue(detector.requiresTools(text), text));
        assertFalse(detector.requiresTools(
                "ถ้าเรายังต้องไปเป็น bare metal แต่ว่าเรามีเครื่องอยู่ 4 เครื่องแบบนี้เราก็ทำแผน horizontal scale ก็ได้ถูกไหม"));
    }

    @Test
    void routesCompletedInvestmentTradesToToolsButKeepsAdviceAsDirectGeneration() {
        List.of(
                "เราขายหุ้น GIL ไปแล้ว",
                "ขาย GIL ไปแล้ว",
                "I sold GIL yesterday",
                "ช่วยบันทึกธุรกรรมขาย WHR",
                "record my AMZN buy",
                "AAA 2 หุ้น @ 35.13 USD หลังหักค่าธรรมเนียมแล้วได้มา 69 USD",
                "WHR 35.13USD หลังหักค่าธรรมเนียมแล้วได้มา 0.95 USD\n"
                        + "SPOT 523.37 หลังหักค่าธรรมเนียมได้มา 1.01 USD")
                .forEach(text -> assertTrue(detector.requiresTools(text), text));
        assertFalse(detector.requiresTools("ควรขายหุ้น GIL ไหม"));
    }
}
