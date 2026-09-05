package com.minikun.conversation.continuity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConversationContinuityResolverTest {
    private final ConversationContinuityResolver resolver = new ConversationContinuityResolver();

    @Test
    void resolvesAmbiguousVisualFollowUpToThePreviousArtist() {
        ConversationContinuity continuity = resolver.resolve(
                "มีอะไรที่น่าสนใจอีกไหม ให้มินิคุงคืนรูปที่ค้นหามา",
                "user: ช่วยค้นหาข้อมูลของนักวาดที่ชื่อ RenaRaziel หน่อย\n"
                        + "assistant: RenaRaziel เป็นนักวาดที่น่าสนใจครับ");

        assertTrue(continuity.followUp());
        assertTrue(continuity.visualFollowUp());
        assertEquals("RenaRaziel", continuity.searchAnchor());
        assertEquals("RenaRaziel", continuity.entities().getFirst());
        assertTrue(continuity.resolvedQuery().startsWith("RenaRaziel "));
        assertTrue(continuity.promptInstruction().contains("Do not switch"));
    }

    @Test
    void keepsProductAsAnchorWhenFollowUpOnlyNamesAVariant() {
        ConversationContinuity continuity = resolver.resolve(
                "แล้วรุ่น Pro ล่ะ",
                "user: Mac mini M4 ราคาเท่าไหร่\nassistant: เริ่มต้นที่...");

        assertTrue(continuity.followUp());
        assertEquals("Mac mini M4", continuity.searchAnchor());
        assertTrue(continuity.resolvedQuery().contains("รุ่น Pro"));
    }

    @Test
    void doesNotInheritContextWhenTheMessageNamesItsOwnSubject() {
        ConversationContinuity continuity = resolver.resolve(
                "ผลงานของ RenaRaziel วันนี้",
                "user: Mac mini M4 ราคาเท่าไหร่\nassistant: เริ่มต้นที่...");

        assertFalse(continuity.followUp());
    }

    @Test
    void doesNotResolveWithoutRecentUserContext() {
        assertEquals(ConversationContinuity.NONE, resolver.resolve("มีรูปอีกไหม", ""));
    }

    @Test
    void resolvesExplicitThaiSearchFollowUpToThePreviousTopic() {
        ConversationContinuity continuity = resolver.resolve(
                "ค้นข้อมูลให้",
                "user: ตอนนี้ Java ล่าสุดคือเวอร์ชันอะไร\nassistant: ขอเช็กให้ครับ");

        assertTrue(continuity.followUp());
        assertTrue(continuity.resolvedQuery().contains("Java"));
        assertTrue(continuity.resolvedQuery().endsWith("ค้นข้อมูลให้"));
    }

    @Test
    void resolvesThaiReferenceToThePreviousTopic() {
        ConversationContinuity continuity = resolver.resolve(
                "ช่วยเช็กเรื่องเมื่อกี้ให้หน่อย",
                "user: Spring Boot รุ่นล่าสุดรองรับ Java อะไรบ้าง\nassistant: ...");

        assertTrue(continuity.followUp());
        assertTrue(continuity.resolvedQuery().contains("Spring Boot"));
        assertTrue(continuity.resolvedQuery().contains("เรื่องเมื่อกี้"));
    }

    @Test
    void resolvesResponseRevisionToThePreviousCreativeRequest() {
        ConversationContinuity continuity = resolver.resolve(
                "มันสั้นไปหน่อย แบ่งเป็นคำตอบละบทแทน",
                "user: มินิคุง เล่าเรื่องแฟนตาซีให้ฟังหน่อย\nassistant: กาลครั้งหนึ่ง...");

        assertTrue(continuity.followUp());
        assertTrue(continuity.resolvedQuery().contains("เล่าเรื่องแฟนตาซี"));
    }
}
