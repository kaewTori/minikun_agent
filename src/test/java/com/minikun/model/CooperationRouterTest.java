package com.minikun.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CooperationRouterTest {
    private final CooperationRouter router = new CooperationRouter();

    @Test
    void routesOrdinaryConversationToOllamaOnly() {
        CooperationRoutingDecision decision = router.decide("ช่วยเล่าเรื่องแมวให้ฟังหน่อย");

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
    void routesHighRiskDomainToBlockingExpert() {
        CooperationRoutingDecision decision = router.decide("ยานี้มีผลข้างเคียงอะไรบ้าง");

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
