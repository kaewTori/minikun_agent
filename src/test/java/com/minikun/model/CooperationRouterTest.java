package com.minikun.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CooperationRouterTest {
    private final CooperationRouter router = new CooperationRouter();

    @Test
    void routesStorytellingToCreativeOllamaPath() {
        CooperationRoutingDecision decision = router.decide("ช่วยเล่าเรื่องแมวให้ฟังหน่อย");

        assertEquals(CooperationRisk.LOW, decision.risk());
        assertEquals("creative_request", decision.reason());
        assertTrue(!decision.needsExpert());
    }

    @Test
    void routesThaiGreetingWritingToCreativeOllamaPath() {
        CooperationRoutingDecision decision = router.decide("ช่วยแต่งคำอวยพรวันเกิดน่ารัก ๆ");

        assertEquals("creative_request", decision.reason());
        assertTrue(!decision.needsExpert());
    }

    @Test
    void doesNotEscalateAnOrdinaryQuestionMarkToTinyGrad() {
        CooperationRoutingDecision decision = router.decide("วันนี้เป็นยังไงบ้าง?");

        assertEquals(CooperationRisk.LOW, decision.risk());
        assertTrue(!decision.needsExpert());
    }

    @Test
    void leavesFreshnessLookupToSearchInsteadOfTinyGrad() {
        CooperationRoutingDecision decision = router.decide("ข่าวล่าสุดวันนี้มีอะไรบ้าง?");

        assertEquals(CooperationRisk.LOW, decision.risk());
        assertTrue(!decision.needsExpert());
    }

    @Test
    void routesPrecisionQuestionToBackgroundExpert() {
        CooperationRoutingDecision decision = router.decide("ช่วยตรวจสอบโค้ดนี้ว่าผิดตรงไหน");

        assertEquals(CooperationRisk.MEDIUM, decision.risk());
        assertTrue(decision.needsExpert());
    }

    @Test
    void routesJavaCapacityPlanningToExpert() {
        CooperationRoutingDecision decision = router.decide(
                "มินิคุง เรามี micro service ที่เป็น jdk25 spring boot 4.1 start แบบ bare metal "
                        + "บนเครื่อง ram32 gb และต้อง start จำนวน 130 app เราจะต้อง config java ยังไง");

        assertEquals(CooperationRisk.MEDIUM, decision.risk());
        assertEquals("calculation_request", decision.reason());
        assertTrue(decision.needsExpert());
    }

    @Test
    void routesCodeWorkToExpertWithoutQuestionMarkOrPrecisionKeyword() {
        CooperationRoutingDecision decision = router.decide("อธิบาย lifecycle ของ Spring bean");

        assertEquals(CooperationRisk.MEDIUM, decision.risk());
        assertEquals("technical_work", decision.reason());
        assertTrue(decision.needsExpert());
    }

    @Test
    void routesResourceCalculationToExpert() {
        CooperationRoutingDecision decision = router.decide("แบ่ง RAM 32 GB ให้ 130 instances");

        assertEquals(CooperationRisk.MEDIUM, decision.risk());
        assertEquals("calculation_request", decision.reason());
        assertTrue(decision.needsExpert());
    }

    @Test
    void doesNotTreatAnOrdinaryCountAsCalculation() {
        CooperationRoutingDecision decision = router.decide("เมื่อวานเจอแมว 2 ตัวแล้วไปกินอาหาร");

        assertEquals(CooperationRisk.LOW, decision.risk());
        assertTrue(!decision.needsExpert());
    }

    @Test
    void routesHighRiskDomainToBlockingExpert() {
        CooperationRoutingDecision decision = router.decide("ยานี้มีผลข้างเคียงอะไรบ้าง");

        assertEquals(CooperationRisk.HIGH, decision.risk());
        assertTrue(decision.needsExpert());
    }

    @Test
    void doesNotConfuseThaiWantWordWithMedicine() {
        CooperationRoutingDecision decision = router.decide("มีรูปผลงานที่น่าสนใจอยากแนะนำไหม");

        assertEquals(CooperationRisk.LOW, decision.risk());
        assertTrue(!decision.needsExpert());
    }

    @Test
    void doesNotConfuseSignalWithContract() {
        CooperationRoutingDecision decision = router.decide("สัญญาณอินเทอร์เน็ตวันนี้ไม่ค่อยดี");

        assertEquals(CooperationRisk.LOW, decision.risk());
        assertTrue(!decision.needsExpert());
    }

    @Test
    void stillRoutesThaiMedicineWithoutWhitespace() {
        CooperationRoutingDecision decision = router.decide("ควรกินยานี้หลังอาหารไหม");

        assertEquals(CooperationRisk.HIGH, decision.risk());
        assertTrue(decision.needsExpert());
    }

    @Test
    void keepsCreativeWritingOnOllamaEvenWhenThePromptIsLong() {
        CooperationRoutingDecision decision = router.decide(
                "ช่วยแต่งเรื่องสั้นแนวแฟนตาซีที่มีตัวละครหลายตัวและช่วยเล่าให้ละเอียดมาก ๆ "
                        + "พร้อมบทสนทนาและฉากจบที่หักมุม");

        assertEquals(CooperationRisk.LOW, decision.risk());
        assertEquals("creative_request", decision.reason());
        assertTrue(!decision.needsExpert());
    }
}
