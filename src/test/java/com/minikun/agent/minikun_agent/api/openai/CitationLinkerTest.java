package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSelection;
import com.minikun.pcs.KnowledgeSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class CitationLinkerTest {
    private static final KnowledgeCandidate FIRST = new KnowledgeCandidate(
            "search-1", KnowledgeSource.SEARCH,
            "Spring AI documentation (https://spring.io/ai): supported evidence", 0,
            "https://spring.io/ai");
    private static final KnowledgeCandidate SECOND = new KnowledgeCandidate(
            "search-2", KnowledgeSource.SEARCH,
            "Spring Boot system requirements (https://docs.spring.io/spring-boot/system-requirements.html): fact", 1,
            "https://docs.spring.io/spring-boot/system-requirements.html");

    @Test
    void replacesInternalEvidenceIdsWithDescriptiveLinks() {
        String result = CitationLinker.normalize(
                "ข้อมูลนี้ยืนยันแล้ว [search-1, search-2]",
                CitationLinker.from(new KnowledgeSelection(List.of(FIRST, SECOND), false)));

        assertFalse(result.contains("search-1"));
        assertFalse(result.contains("search-2"));
        assertTrue(result.contains("[Spring AI documentation](https://spring.io/ai)"));
        assertTrue(result.contains("[Spring Boot system requirements]"
                + "(https://docs.spring.io/spring-boot/system-requirements.html)"));
    }

    @Test
    void hidesInternalReferenceWhenNoRealUrlExistsAndPreservesMarkdownLinks() {
        KnowledgeCandidate missing = new KnowledgeCandidate(
                "search-1", KnowledgeSource.SEARCH, "uncited evidence", 0, "");
        CitationLinker.Context context = CitationLinker.from(
                new KnowledgeSelection(List.of(missing), false));

        assertEquals("ข้อความครับ", CitationLinker.normalize("ข้อความครับ [search-1]", context).strip());
        assertEquals("อ่าน [เอกสาร](https://example.com/docs) ได้ครับ",
                CitationLinker.normalize("อ่าน [เอกสาร](https://example.com/docs) ได้ครับ", context));
    }

    @Test
    void rewritesCitationSplitAcrossStreamingChunks() {
        CitationLinker.Context context = CitationLinker.from(
                new KnowledgeSelection(List.of(FIRST, SECOND), false));
        CitationLinker.Stream stream = CitationLinker.stream(context);

        String result = stream.accept("ข้อมูล [sea")
                + stream.accept("rch-1, search-")
                + stream.accept("2] ครับ")
                + stream.finish();

        assertEquals(CitationLinker.normalize("ข้อมูล [search-1, search-2] ครับ", context), result);
        assertFalse(result.contains("search-1"));
    }
}
