package com.minikun.personality.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationPolicyEngineTest {
    private final ConversationPolicyEngine engine = new ConversationPolicyEngine();

    @Test
    void listensWithoutUnsolicitedAdviceWhenUserWantsToVent() {
        ConversationPolicy policy = engine.evaluate("วันนี้เหนื่อยมาก แค่อยากระบาย ไม่ต้องแนะนำนะ", List.of());

        assertEquals(ConversationIntent.VENT, policy.intent());
        assertEquals(UserNeed.LISTEN_FIRST, policy.userNeed());
        assertFalse(policy.suggestAction());
        assertEquals(1, policy.questionBudget());
        assertTrue(policy.acknowledgeEmotion());
    }

    @Test
    void executesHighInitiativeRequests() {
        ConversationPolicy policy = engine.evaluate("ลุย implement feature นี้ให้ครบเลย", List.of());

        assertEquals(ConversationIntent.ACT, policy.intent());
        assertEquals(UserNeed.EXECUTE, policy.userNeed());
        assertEquals(InitiativeLevel.HIGH, policy.initiative());
        assertEquals(0, policy.questionBudget());
    }

    @Test
    void challengesInsteadOfAutomaticallyAgreeingWhenAsked() {
        ConversationPolicy policy = engine.evaluate("คิดว่าเราควรลาออกไหม พูดตรง ๆ อย่าตามใจ", List.of());

        assertEquals(ConversationIntent.DECIDE, policy.intent());
        assertEquals(UserNeed.CHALLENGE, policy.userNeed());
        assertEquals(ChallengeLevel.DIRECT, policy.challengeLevel());
        assertTrue(policy.promptInstruction().contains("Do not agree automatically"));
    }

    @Test
    void carriesWeakEmotionAcrossEllipticalFollowUp() {
        ConversationPolicy policy = engine.evaluate("ยังเลย", List.of(
                new ChatMessage("user", "ช่วงนี้เราเหนื่อยและเครียดมาก"),
                new ChatMessage("assistant", "วันนี้ได้พักบ้างไหมครับ")));

        assertTrue(policy.acknowledgeEmotion());
    }
}
