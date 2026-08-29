package com.minikun.conversation.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.conversation.continuity.ConversationContinuity;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSelection;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.pcs.SearchContext;
import com.minikun.pcs.model.ImageSource;
import com.minikun.search.model.SearchDecisionReason;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationRepairAdvisorTest {
    private final ConversationRepairAdvisor advisor = new ConversationRepairAdvisor();
    private final ConversationContinuity continuity = new ConversationContinuity(
            true,
            "ช่วยค้นหาข้อมูลของนักวาดที่ชื่อ RenaRaziel หน่อย",
            "RenaRaziel",
            List.of("RenaRaziel"),
            "RenaRaziel มีรูปผลงานอีกไหม",
            true,
            0.94);

    @Test
    void blocksEvidenceThatSilentlySwitchesToAnotherArtist() {
        KnowledgeSelection knowledge = new KnowledgeSelection(List.of(
                new KnowledgeCandidate("search-1", KnowledgeSource.SEARCH,
                        "Portfolio and illustrations by AnotherArtist", 0, "https://example.com")), false);

        ConversationRepairAdvice advice = advisor.advise(continuity, knowledge, attemptedSearch());

        assertTrue(advice.required());
        assertEquals(ConversationRepairAdvice.Reason.SUBJECT_MISMATCH, advice.reason());
        assertTrue(advice.instruction().contains("Do not silently replace"));
    }

    @Test
    void reportsMissingVisualWithoutClaimingImagesAreUnsupported() {
        ConversationRepairAdvice advice = advisor.advise(
                continuity, KnowledgeSelection.EMPTY, attemptedSearch());

        assertEquals(ConversationRepairAdvice.Reason.VISUAL_RESULT_MISSING, advice.reason());
        assertTrue(advice.instruction().contains("cannot display images"));
    }

    @Test
    void keepsASelfCheckWhenEvidenceMatchesTheResolvedSubject() {
        KnowledgeSelection knowledge = new KnowledgeSelection(
                List.of(), false, List.of(new ImageSource(
                        "https://images.example/rena.jpg", "RenaRaziel artwork",
                        "https://portfolio.example/rena", "Illustration by RenaRaziel")));

        ConversationRepairAdvice advice = advisor.advise(continuity, knowledge, attemptedSearch());

        assertEquals(ConversationRepairAdvice.Reason.FOLLOW_UP_GUARD, advice.reason());
        assertTrue(advice.required());
    }

    @Test
    void ignoresStandaloneMessages() {
        ConversationRepairAdvice advice = advisor.advise(
                ConversationContinuity.NONE, KnowledgeSelection.EMPTY, SearchContext.EMPTY);

        assertFalse(advice.required());
    }

    private SearchContext attemptedSearch() {
        return new SearchContext(
                "มีรูปผลงานอีกไหม", true, false, true, true, true, true,
                SearchDecisionReason.IMAGE_REQUEST);
    }
}
