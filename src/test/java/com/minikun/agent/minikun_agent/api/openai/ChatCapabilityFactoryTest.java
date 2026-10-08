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
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import com.minikun.search.model.SearchPlanHints;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatCapabilityFactoryTest {
    @Test
    void topUpGuidanceUsesThePortfolioAndLatestBudgetWithoutHardcodingAFundOrAllowingOrders() {
        var factory = new ChatCapabilityFactory();
        String history = "user: เรามีอยู่ 5000 บาทเอาไปเติมอะไรดีวันนี้\nassistant: เสนอแผนตามพอร์ต"
                + "\nuser: เปลี่ยนใหม่เป็น 3800 บาท\nassistant: ปรับแผนแล้ว";
        String instruction = factory.investmentAdvice("ขอยอดเป็น $ หน่อย", history, true).orElseThrow().content();
        assertTrue(instruction.contains("MINIKUN_INVESTMENT_ADVICE_REQUIRED"));
        assertTrue(instruction.contains("converted_amount"));
        assertTrue(instruction.contains("replace the old budget"));
        assertTrue(instruction.contains("costAllocationPercent"));
        assertTrue(instruction.contains("never authorize a transaction"));
        org.junit.jupiter.api.Assertions.assertFalse(instruction.contains("VTI"));
        String unavailable = factory.investmentAdvice("แนะนำเติมพอร์ตวันนี้", "", false).orElseThrow().content();
        assertTrue(unavailable.contains("current ledger, prices and FX have not been checked"));
        assertTrue(factory.investmentAdvice("ขอยอดเป็น $ หน่อย", "user: ค่าจองโรงแรม", true).isEmpty());
    }

    @Test
    void preservesCompleteSourceUrlsWhenEvidenceSnippetsAreShortened() {
        String url = "https://example.com/" + "%E0%B8%94".repeat(100);
        var source = new KnowledgeCandidate("search-1", KnowledgeSource.SEARCH,
                "บทความ (" + url + "): " + "evidence ".repeat(100), 0, url);
        var knowledge = new ChatKnowledgeSelection(new KnowledgeSelection(List.of(source), false),
                KnowledgeConsolidation.EMPTY, new SearchSelectionSignals(true), SearchContext.EMPTY);
        String evidence = new ChatCapabilityFactory().create("ค้นข้อมูล", knowledge,
                null, null, null, null, "", false).stream()
                .filter(capability -> capability.name().equals("Web search evidence"))
                .map(capability -> capability.content()).findFirst().orElseThrow();
        assertTrue(evidence.contains("Source URL: " + url));
        assertTrue(evidence.contains("[search-1]"));
        assertTrue(evidence.contains("The application resolves the ID"));
        org.junit.jupiter.api.Assertions.assertFalse(evidence.contains("(" + url.substring(0, 100)));
    }

    @Test
    void requiresPresentationToolForActualDeckRequests() {
        var knowledge = new ChatKnowledgeSelection(KnowledgeSelection.EMPTY, KnowledgeConsolidation.EMPTY,
                SearchSelectionSignals.EMPTY, SearchContext.EMPTY);
        String requested = new ChatCapabilityFactory().create("ช่วยทำสไลด์แบบสวย ๆ ให้หน่อย", knowledge,
                null, null, null, null, "", true).stream()
                .map(capability -> capability.name() + " " + capability.content())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        String planning = new ChatCapabilityFactory().create("ช่วยวางแผนให้มินิคุงทำสไลด์ได้", knowledge,
                null, null, null, null, "", true).stream()
                .map(capability -> capability.content()).reduce((left, right) -> left + "\n" + right).orElse("");

        assertTrue(requested.contains("MINIKUN_PRESENTATION_CREATE_REQUIRED"));
        assertTrue(requested.contains("MUST call presentation.create"));
        assertTrue(requested.contains("make a fresh tool call"));
        org.junit.jupiter.api.Assertions.assertFalse(planning.contains("MINIKUN_PRESENTATION_CREATE_REQUIRED"));
    }

    @Test
    void visualCapabilityReportsConfiguredFormatsAndPendingStatus() {
        var factory = new ChatCapabilityFactory();
        var planned = factory.visualOutput(true, true);
        assertTrue(planned.required());
        assertTrue(planned.content().contains("SVG เป็นไฟล์ภาพที่ดูและดาวน์โหลดได้"));
        assertTrue(planned.content().contains("รอบนี้ระบบวางแผนสร้างภาพ"));
        assertTrue(planned.content().contains("อย่าอ้างว่าสร้างเสร็จแล้วก่อนมีผลสำเร็จ"));
        assertTrue(planned.content().contains("คำปฏิเสธเรื่องความสามารถในประวัติคำตอบเก่าไม่ใช่หลักฐาน"));
        assertTrue(factory.visualOutput(true, false).content().contains("รอบนี้ยังไม่มีแผนสร้างภาพ"));
        org.junit.jupiter.api.Assertions.assertFalse(factory.visualOutput(false, true).content().contains("SVG"));
    }

    @Test
    void memoryGuidancePrioritizesUserCorrectionsAndGroundsSourceClaims() {
        var memory = new KnowledgeCandidate("memory-1", KnowledgeSource.MEMORY,
                "PREFERENCE: ไม่ชอบหวาน (source=USER_DIRECTIVE)", 0, "conversation:chat-1");
        var knowledge = new ChatKnowledgeSelection(
                new KnowledgeSelection(List.of(memory), false), KnowledgeConsolidation.EMPTY,
                SearchSelectionSignals.EMPTY, SearchContext.EMPTY);

        String text = new ChatCapabilityFactory().create("ฉันชอบอะไร", knowledge,
                null, null, null, null, "", false).stream()
                .map(capability -> capability.name() + " " + capability.content())
                .reduce((left, right) -> left + "\n" + right).orElse("");

        assertTrue(text.contains("Personal memory"));
        assertTrue(text.contains("explicit user corrections take precedence"));
        assertTrue(text.contains("extracted facts backed by user quotes, and unsupported inferences"));
        assertTrue(text.contains("say when no verifiable quote is available"));
    }

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
        assertTrue(text.contains("Minikun narrative voice"));
        assertTrue(text.contains("choose the narrative form and pacing"));
        assertTrue(text.contains("directness means honoring the premise"));
        assertTrue(text.contains("fixed beginning-middle-end shape"));
        assertTrue(text.contains("Invent details only for fiction"));
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
    void localDiscoveryAddsPracticalGuideInstructions() {
        KnowledgeCandidate source = new KnowledgeCandidate(
                "search-0", KnowledgeSource.SEARCH,
                "Museum (https://example.org/museum): quiet place", 0, "https://example.org/museum");
        String query = "สนใจพิพิธภัณฑ์เงียบ ๆ ในเชียงใหม่";
        ChatKnowledgeSelection knowledge = new ChatKnowledgeSelection(
                new KnowledgeSelection(List.of(source), false), KnowledgeConsolidation.EMPTY,
                new SearchSelectionSignals(true), SearchContext.EMPTY, ResearchTrace.EMPTY,
                new SearchDecision(true, query, SearchDecisionReason.EXTERNAL_RESOURCE,
                        new SearchPlanHints("local_discovery", 0.96, "พิพิธภัณฑ์ เชียงใหม่ เงียบ", List.of(),
                                List.of("opening_hours", "location", "price", "atmosphere"), "เชียงใหม่")));

        var capabilities = new ChatCapabilityFactory().create(
                query, knowledge, null, null, null, null, "", false);
        String text = capabilities.stream().map(capability -> capability.name() + "\n" + capability.content())
                .reduce((left, right) -> left + "\n" + right).orElse("");

        assertTrue(text.contains("Local guide"));
        assertTrue(text.contains("3 to 5"));
        assertTrue(text.contains("area, budget"));
        assertTrue(text.contains("opening hours"));
        assertTrue(text.contains("real source URL"));
        assertTrue(text.contains("at most one concise"));
    }

    @Test
    void nearbyRequestWithoutVerifiedAreaForbidsInventingCurrentPlace() {
        ChatKnowledgeSelection knowledge = new ChatKnowledgeSelection(
                KnowledgeSelection.EMPTY, KnowledgeConsolidation.EMPTY,
                new SearchSelectionSignals(true), SearchContext.EMPTY,
                ResearchTrace.EMPTY, null, DeviceLocationContext.unavailable());

        var capabilities = new ChatCapabilityFactory().create(
                "แนะนำร้านอาหารแถวนี้", knowledge, null, null, null, null, "", false);
        String text = capabilities.stream().map(capability -> capability.name() + "\n" + capability.content())
                .reduce((left, right) -> left + "\n" + right).orElse("");

        assertTrue(text.contains("Device location"));
        assertTrue(text.contains("Do not guess or state the user's current place"));
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
        assertTrue(text.contains("Do not call or simulate"));
        assertTrue(text.contains("never output tool markup"));
        assertTrue(!text.contains("If a native web-search tool is available"));
    }
}
