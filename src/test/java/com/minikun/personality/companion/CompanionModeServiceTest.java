package com.minikun.personality.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CompanionModeServiceTest {
    @Test
    void activatesCompanionModeAndKeepsItForTheConversation() {
        CompanionModeService service = new CompanionModeService(true, 100);

        CompanionModeContext changed = service.evaluate(
                "owner", "conversation", "วันนี้ขอคุยแบบคู่หูนะ").orElseThrow();
        CompanionModeContext continued = service.evaluate(
                "owner", "conversation", "วันนี้เราเหนื่อยนิดหน่อย").orElseThrow();

        assertEquals(CompanionMode.COMPANION, changed.mode());
        assertTrue(changed.explicitlyChanged());
        assertEquals(CompanionMode.COMPANION, continued.mode());
        assertFalse(continued.explicitlyChanged());
        assertTrue(continued.instruction().contains("ศูนย์หรือหนึ่งคำถาม"));
        assertTrue(continued.instruction().contains("ไม่เกินสองนาที"));
        assertTrue(continued.instruction().contains("ห้ามเสนอหลายทางเลือก"));
    }

    @Test
    void supportsWorkAndFocusModesWithoutLeakingAcrossConversations() {
        CompanionModeService service = new CompanionModeService(true, 100);

        assertEquals(CompanionMode.WORK,
                service.evaluate("owner", "work", "เข้าโหมดทำงาน").orElseThrow().mode());
        assertEquals(CompanionMode.FOCUS,
                service.evaluate("owner", "focus", "เปิดโหมดโฟกัส").orElseThrow().mode());
        assertTrue(service.evaluate("owner", "other", "ทำอะไรต่อดี").isEmpty());
        assertTrue(service.evaluate("another-owner", "work", "ทำอะไรต่อดี").isEmpty());
    }

    @Test
    void resetAppliesBalancedInstructionForCurrentTurnThenClearsMode() {
        CompanionModeService service = new CompanionModeService(true, 100);
        service.evaluate("owner", "conversation", "เข้าโหมดคู่หู");

        CompanionModeContext reset = service.evaluate(
                "owner", "conversation", "กลับโหมดปกติ").orElseThrow();

        assertEquals(CompanionMode.BALANCED, reset.mode());
        assertTrue(reset.explicitlyChanged());
        assertTrue(service.activeMode("owner", "conversation").isEmpty());
        assertTrue(service.evaluate("owner", "conversation", "คุยกันต่อ").isEmpty());
    }

    @Test
    void ignoresModeRequestsWhenDisabled() {
        CompanionModeService service = new CompanionModeService(false, 100);

        assertTrue(service.evaluate("owner", "conversation", "เข้าโหมดคู่หู").isEmpty());
        assertTrue(service.activeMode("owner", "conversation").isEmpty());
    }

    @Test
    void doesNotTreatNegatedPhraseAsModeChange() {
        CompanionModeService service = new CompanionModeService(true, 100);
        service.evaluate("owner", "conversation", "เข้าโหมดคู่หู");

        CompanionModeContext current = service.evaluate(
                "owner", "conversation", "ยังไม่ต้องออกจากโหมดนะ").orElseThrow();

        assertEquals(CompanionMode.COMPANION, current.mode());
        assertFalse(current.explicitlyChanged());
    }
}
