package com.minikun.research;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSelection;
import com.minikun.pcs.KnowledgeSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResearchStorytellingAdvisorTest {
    private final ResearchStorytellingAdvisor advisor = new ResearchStorytellingAdvisor();

    @Test
    void deepResearchWithWebEvidenceRequiresSynthesisCitationsAndNarrativeCraft() {
        KnowledgeCandidate source = new KnowledgeCandidate(
                "search-0", KnowledgeSource.SEARCH,
                "Official report (https://example.org/report): supported evidence", 0,
                "https://example.org/report");

        var capabilities = advisor.advise(
                "ค้นคว้าและวิเคราะห์เรื่องนี้แบบเจาะลึก",
                new KnowledgeSelection(List.of(source), false));
        String text = capabilities.stream().map(capability -> capability.name() + "\n" + capability.content())
                .reduce((left, right) -> left + "\n" + right).orElse("");

        assertTrue(text.contains("Deep research workflow"));
        assertTrue(text.contains("Evidence and citations"));
        assertTrue(text.contains("Narrative craft: ANALYSIS"));
        assertTrue(text.contains("adjacent citation"));
        assertTrue(text.contains("Surface meaningful disagreement"));
        assertTrue(text.contains("specific action, consequence, and change"));
        assertTrue(text.contains("stable point of view and tense"));
        assertTrue(capabilities.stream().allMatch(com.minikun.pcs.model.CapabilityInstruction::required));
    }

    @Test
    void unsupportedOrdinaryTurnAddsNoResearchPromptWeight() {
        var capabilities = advisor.advise("สวัสดีมินิคุง", KnowledgeSelection.EMPTY);

        assertTrue(capabilities.isEmpty());
        assertFalse(capabilities.stream().anyMatch(capability -> capability.name().contains("research")));
    }

    @Test
    void storyGuidanceAllowsNonlinearFormsWhenThePromptCallsForThem() {
        var capabilities = advisor.advise("ช่วยเล่าเรื่องราวลึกลับให้ฟัง", KnowledgeSelection.EMPTY);
        String text = capabilities.stream().map(com.minikun.pcs.model.CapabilityInstruction::content)
                .reduce((left, right) -> left + "\n" + right).orElse("");

        assertTrue(text.contains("Choose the form and structure that best fit the user's intent"));
        assertTrue(text.contains("do not force a linear beginning-middle-end shape"));
    }
}
