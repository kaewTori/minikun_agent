package com.minikun.personality.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.personality.model.Mood;
import org.junit.jupiter.api.Test;

class ConversationStyleAdvisorTest {
    private final ConversationStyleAdvisor advisor = new ConversationStyleAdvisor();

    @Test
    void keepsOrdinaryConversationWarmAndDirect() {
        ConversationStyleAdvisor.ConversationStyle style = advisor.advise("วันนี้กินอะไรดี");

        assertEquals(Mood.CALM, style.mood().mood());
        assertFalse(style.mood().active());
        assertTrue(style.instruction().contains("actual intent directly"));
        assertTrue(style.instruction().contains("implied follow-ups"));
        assertTrue(style.instruction().contains("warm, relaxed, and unforced"));
    }

    @Test
    void respondsSupportivelyToEmotionalCuesWithoutBecomingScripted() {
        ConversationStyleAdvisor.ConversationStyle style = advisor.advise("วันนี้เหนื่อยและเครียดมากเลย");

        assertEquals(Mood.SUPPORTIVE, style.mood().mood());
        assertTrue(style.mood().active());
        assertTrue(style.instruction().contains("acknowledge the specific feeling"));
        assertTrue(style.instruction().contains("one small, practical next step"));
        assertTrue(style.instruction().contains("self-reference, catchphrases"));
    }

    @Test
    void prioritizesUrgentDistressOverGeneralEmotion() {
        ConversationStyleAdvisor.ConversationStyle style = advisor.advise("เครียดมากและรู้สึกหายใจไม่ออก");

        assertEquals(Mood.CONCERNED, style.mood().mood());
        assertTrue(style.instruction().contains("prioritize immediate safety"));
    }

    @Test
    void adaptsFocusedAndPlayfulTurns() {
        assertEquals(Mood.FOCUSED, advisor.advise("ช่วยวิเคราะห์โค้ดนี้").mood().mood());
        assertEquals(Mood.PLAYFUL, advisor.advise("แซวเล่นนะ 555").mood().mood());
        assertEquals(Mood.PLAYFUL, advisor.advise("ขำจนหายใจไม่ออก 555").mood().mood());
        assertEquals(Mood.CALM, advisor.advise("tell me about this planet").mood().mood());
    }
}
