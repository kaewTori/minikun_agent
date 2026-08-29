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

    @Test
    void creativeContinuationKeepsNaturalEndingGuidanceFromConversationState() {
        var capabilities = new ChatCapabilityFactory().create(
                "ต่อเลย", new ChatKnowledgeSelection(
                        KnowledgeSelection.EMPTY, KnowledgeConsolidation.EMPTY,
                        SearchSelectionSignals.EMPTY, SearchContext.EMPTY),
                null, null, null, null, "", false, true);
        String text = capabilities.stream().map(capability -> capability.name() + "\n" + capability.content())
                .reduce((left, right) -> left + "\n" + right).orElse("");

        assertTrue(text.contains("Creative pacing"));
        assertTrue(text.contains("Reserve enough space"));
        assertTrue(text.contains("scene or chapter"));
        assertTrue(text.contains("protagonist desire, obstacle, stakes"));
        assertTrue(text.contains("stock metaphors"));
    }

    @Test
    void failedSearchForbidsSimulatedSearchAndRequestsFocusedRetry() {
        SearchContext failedSearch = new SearchContext(
                "ช่วยค้นหา RenaRaziel", false, false, true, true, true, false,
                com.minikun.search.model.SearchDecisionReason.FACT_LOOKUP);
        ChatKnowledgeSelection knowledge = new ChatKnowledgeSelection(
                KnowledgeSelection.EMPTY, KnowledgeConsolidation.EMPTY,
                new SearchSelectionSignals(true), failedSearch);

        var capabilities = new ChatCapabilityFactory().create(
                "ช่วยค้นหา RenaRaziel", knowledge, null, null, null, null, "", true);
        String text = capabilities.stream().map(capability -> capability.name() + "\n" + capability.content())
                .reduce((left, right) -> left + "\n" + right).orElse("");

        assertTrue(text.contains("Web search outcome"));
        assertTrue(text.contains("do not narrate a simulated search"));
        assertTrue(text.contains("shorter, focused query"));
    }

    @Test
    void successfulSearchRequiresAnsweringFromEvidenceWithSourceUrls() {
        KnowledgeCandidate source = new KnowledgeCandidate(
                "search-0", KnowledgeSource.SEARCH,
                "S.RenaRaziel (https://www.pixiv.net/en/users/24515230): artist profile", 0,
                "https://www.pixiv.net/en/users/24515230");
        SearchContext successfulSearch = new SearchContext(
                "ช่วยค้นหา RenaRaziel", false, false, true, true, true, true,
                com.minikun.search.model.SearchDecisionReason.FACT_LOOKUP);
        ChatKnowledgeSelection knowledge = new ChatKnowledgeSelection(
                new KnowledgeSelection(List.of(source), false), KnowledgeConsolidation.EMPTY,
                new SearchSelectionSignals(true), successfulSearch);

        var capabilities = new ChatCapabilityFactory().create(
                "ช่วยค้นหา RenaRaziel", knowledge, null, null, null, null, "", false);
        String text = capabilities.stream().map(capability -> capability.name() + "\n" + capability.content())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        String normalizedText = text.replaceAll("\\s+", " ");

        assertTrue(normalizedText.contains("Web search evidence"));
        assertTrue(normalizedText.contains("Answer the original question now"));
        assertTrue(normalizedText.contains("cite its URLs"));
        assertTrue(normalizedText.contains("Do not say information was unavailable"));
        assertTrue(normalizedText.contains("Concrete search evidence"));
        assertTrue(normalizedText.contains("https://www.pixiv.net/en/users/24515230"));
    }

    @Test
    void retrievedImagesForbidFalseCapabilityDenial() {
        SearchContext imageSearch = new SearchContext(
                "ขอดูผลงาน", true, false, true, true, true, true,
                com.minikun.search.model.SearchDecisionReason.IMAGE_REQUEST);
        ChatKnowledgeSelection knowledge = new ChatKnowledgeSelection(
                KnowledgeSelection.EMPTY, KnowledgeConsolidation.EMPTY,
                new SearchSelectionSignals(true), imageSearch);

        var capabilities = new ChatCapabilityFactory().create(
                "ขอดูผลงาน", knowledge, new ImageAwareness(3), null, null, null, "", false);
        String text = capabilities.stream().map(capability -> capability.name() + "\n" + capability.content())
                .reduce((left, right) -> left + "\n" + right).orElse("");

        assertTrue(text.contains("Retrieved Images"));
        assertTrue(text.contains("Do not claim that you cannot display or return images"));
        assertTrue(text.contains("Briefly introduce the attached results"));
    }

    @Test
    void failedImageRetrievalDescribesTheAttemptInsteadOfDenyingCapability() {
        SearchContext imageSearch = new SearchContext(
                "ขอดูผลงาน", true, false, true, true, true, false,
                com.minikun.search.model.SearchDecisionReason.IMAGE_REQUEST);
        ChatKnowledgeSelection knowledge = new ChatKnowledgeSelection(
                KnowledgeSelection.EMPTY, KnowledgeConsolidation.EMPTY,
                new SearchSelectionSignals(true), imageSearch);

        var capabilities = new ChatCapabilityFactory().create(
                "ขอดูผลงาน", knowledge, null, null, null, null, "", false);
        String text = capabilities.stream().map(capability -> capability.name() + "\n" + capability.content())
                .reduce((left, right) -> left + "\n" + right).orElse("");

        assertTrue(text.contains("Image retrieval outcome"));
        assertTrue(text.contains("supports returning search-result images"));
        assertTrue(text.contains("Never claim categorically"));
    }
}
