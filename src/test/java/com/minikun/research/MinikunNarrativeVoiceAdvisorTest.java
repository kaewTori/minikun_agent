package com.minikun.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MinikunNarrativeVoiceAdvisorTest {
    private final MinikunNarrativeVoiceAdvisor advisor = new MinikunNarrativeVoiceAdvisor();

    @Test
    void activatesForAnExplicitStoryRequest() {
        var capability = advisor.advise("เล่าเรื่องเด็กคนหนึ่งที่ตามหาแสงดาว", false);

        assertTrue(capability.isPresent());
        assertEquals("Minikun narrative voice", capability.orElseThrow().name());
        assertTrue(capability.orElseThrow().required());
    }

    @Test
    void staysActiveForAContinuationInsideACreativeConversation() {
        var capability = advisor.advise("ต่อเลย", true);

        assertTrue(capability.isPresent());
    }

    @Test
    void doesNotAlterAnOrdinaryTechnicalAnswer() {
        var capability = advisor.advise("อธิบายวิธีตั้งค่า connection pool", false);

        assertTrue(capability.isEmpty());
    }
}
