package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSelection;
import com.minikun.pcs.KnowledgeSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class GroundedAnswerGuardTest {
    @Test
    void exactSourceRequestNeedsMoreThanSearchSnippets() {
        var snippet = new KnowledgeCandidate("search-1", KnowledgeSource.SEARCH,
                "A search snippet mentions a poem", 0, "https://example.org/poem");
        var knowledge = new KnowledgeSelection(List.of(snippet), false);

        assertEquals(GroundedAnswerGuard.NO_SOURCE,
                GroundedAnswerGuard.beforeModel("ขอข้อความต้นฉบับของบทกวีนี้", "", knowledge));
        assertTrue(GroundedAnswerGuard.required("ทำแบบนี้ทั้งเพลงเลย",
                "user: ขอเนื้อร้องต้นฉบับ\nassistant: ..."));
        assertNull(GroundedAnswerGuard.beforeModel("ช่วยแต่งเรื่องสั้น", "", knowledge));
    }

    @Test
    void exactQuoteMustAppearInRetrievedSource() {
        var page = new KnowledgeCandidate("browser-1", KnowledgeSource.BROWSER,
                "The opening line reads: Welcome to your life. There is no turning back.", 0,
                "https://example.org/source");
        var knowledge = new KnowledgeSelection(List.of(page), false);

        assertNull(GroundedAnswerGuard.beforeModel("ขอข้อความต้นฉบับ", "", knowledge));
        assertEquals(GroundedAnswerGuard.NO_SOURCE, GroundedAnswerGuard.afterModel(
                "[Verse 1]\nA wholly invented opening line with many plausible words in a row", knowledge,
                "ขอเนื้อเพลงต้นฉบับ"));
        assertEquals("[Verse 1]\nWelcome to your life. There is no turning back.",
                GroundedAnswerGuard.afterModel(
                        "[Verse 1]\nWelcome to your life. There is no turning back.", knowledge,
                        "ขอเนื้อเพลงต้นฉบับ"));
        assertEquals(GroundedAnswerGuard.NO_SOURCE, GroundedAnswerGuard.afterModel(
                "[Verse 1]\nWelcome to your life.\nThese made-up words are not in the source.",
                knowledge, "ขอเนื้อเพลงต้นฉบับ"));
    }

    @Test
    void userSuppliedPassageCanBeTranslatedWithoutExternalSource() {
        String query = "แปลข้อความนี้: The sample sentence here is supplied directly by the user for translation.";
        assertNull(GroundedAnswerGuard.beforeModel(query, "user: ขอแปลเนื้อเพลง", KnowledgeSelection.EMPTY));
        assertEquals("[Verse 1]\nThe sample sentence here is supplied directly by the user for translation.",
                GroundedAnswerGuard.afterModel(
                        "[Verse 1]\nThe sample sentence here is supplied directly by the user for translation.",
                        KnowledgeSelection.EMPTY, query));
        assertEquals(GroundedAnswerGuard.NO_SOURCE, GroundedAnswerGuard.afterModel(
                "[Verse 1]\nA different sentence invented by the assistant.",
                KnowledgeSelection.EMPTY, query));
    }
}
