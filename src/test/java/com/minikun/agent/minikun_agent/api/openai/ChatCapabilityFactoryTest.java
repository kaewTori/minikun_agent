package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeConsolidation;
import com.minikun.pcs.KnowledgeSelection;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.pcs.SearchContext;
import com.minikun.pcs.SearchSelectionSignals;
import com.minikun.research.ResearchStopReason;
import com.minikun.research.ResearchTrace;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatCapabilityFactoryTest {
    @Test
    void composesResearchAndStorytellingGuidanceWithResolvedKnowledge() {
        KnowledgeCandidate source = new KnowledgeCandidate(
                "search-0", KnowledgeSource.SEARCH,
                "Report (https://example.org): factual evidence", 0, "https://example.org");
        ChatKnowledgeSelection knowledge = new ChatKnowledgeSelection(
                new KnowledgeSelection(List.of(source), false),
                KnowledgeConsolidation.EMPTY,
                new SearchSelectionSignals(true),
                SearchContext.EMPTY);

        var capabilities = new ChatCapabilityFactory().create(
                "ช่วยค้นคว้าแล้วเล่าเป็นเรื่องที่เข้าใจง่าย",
                knowledge, null, null, null, null, "", false);
        String names = capabilities.stream().map(com.minikun.pcs.model.CapabilityInstruction::name)
                .reduce((left, right) -> left + "\n" + right).orElse("");

        assertTrue(names.contains("Deep research workflow"));
        assertTrue(names.contains("Evidence and citations"));
        assertTrue(names.contains("Narrative craft: STORY"));
    }

    @Test
    void exposesAutonomousLoopTraceAndUnresolvedGapsToFinalSynthesis() {
        KnowledgeCandidate source = new KnowledgeCandidate(
                "search-0", KnowledgeSource.SEARCH, "Evidence", 0, "https://example.org");
        ChatKnowledgeSelection knowledge = new ChatKnowledgeSelection(
                new KnowledgeSelection(List.of(source), false), KnowledgeConsolidation.EMPTY,
                new SearchSelectionSignals(true), SearchContext.EMPTY,
                new ResearchTrace("objective", List.of("question"), List.of("query 1", "query 2"),
                        2, ResearchStopReason.NO_FOLLOW_UPS, List.of("missing counter-evidence"), true));

        var capabilities = new ChatCapabilityFactory().create(
                "ช่วยค้นคว้าเรื่องนี้", knowledge, null, null, null, null, "", false);
        String text = capabilities.stream().map(capability -> capability.name() + "\n" + capability.content())
                .reduce((left, right) -> left + "\n" + right).orElse("");

        assertTrue(text.contains("Autonomous research loop result"));
        assertTrue(text.contains("Autonomous research iterations: 2"));
        assertTrue(text.contains("missing counter-evidence"));
        assertTrue(text.contains("mandatory limitations"));
    }
}
