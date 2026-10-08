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

    @Test
    void repairsOnlyAnUnambiguousTruncatedUrlUsingTheActualSource() {
        String url = "https://www.facebook.com/prberd/posts/" + "%E0%B8%94".repeat(100) + "?a=1&b=2";
        KnowledgeCandidate source = new KnowledgeCandidate("search-1", KnowledgeSource.SEARCH,
                "บทความ DCA (" + url + "): evidence", 0, url);
        var context = CitationLinker.from(new KnowledgeSelection(List.of(source), false));
        String shortened = url.substring(0, 80) + "...";
        String damaged = "อ่าน [" + shortened + "](" + shortened;
        assertEquals("อ่าน [บทความ DCA](" + url + ")", CitationLinker.normalize(damaged, context));

        var stream = CitationLinker.stream(context);
        StringBuilder streamed = new StringBuilder();
        for (char character : damaged.toCharArray()) streamed.append(stream.accept(String.valueOf(character)));
        streamed.append(stream.finish());
        assertEquals(CitationLinker.normalize(damaged, context), streamed.toString());

        KnowledgeCandidate alternate = new KnowledgeCandidate("search-2", KnowledgeSource.SEARCH,
                "Other (" + url + "/other): evidence", 1, url + "/other");
        var ambiguous = CitationLinker.from(new KnowledgeSelection(List.of(source, alternate), false));
        assertEquals(damaged, CitationLinker.normalize(damaged, ambiguous));
    }

    @Test
    void streamsACompleteLongLinkAndBalancedParenthesesWithoutBreakingTheTarget() {
        for (String url : List.of("https://example.com/" + "%E0%B8%94".repeat(100),
                "https://example.com/Function_(mathematics)")) {
            var source = new KnowledgeCandidate("search-1", KnowledgeSource.SEARCH,
                    "Reference (" + url + "): evidence", 0, url);
            var context = CitationLinker.from(new KnowledgeSelection(List.of(source), false));
            String content = "Read [" + url + "](" + url + ") now";
            var stream = CitationLinker.stream(context);
            StringBuilder streamed = new StringBuilder();
            for (char character : content.toCharArray()) streamed.append(stream.accept(String.valueOf(character)));
            streamed.append(stream.finish());
            assertEquals(CitationLinker.normalize(content, context), streamed.toString());
            assertTrue(streamed.toString().contains(url));
            assertFalse(streamed.toString().contains("[https://"));
        }
    }
}
