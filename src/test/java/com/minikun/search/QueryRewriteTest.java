package com.minikun.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.minikun.search.internal.DefaultSearchQueryRewriteService;
import com.minikun.search.model.SearchQuery;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class QueryRewriteTest {
    @Test
    void normalizationPreservesExactInputAndCanonicalizesWhitespace() {
        String input = new String("  Latest   News  ");
        SearchQuery result = new DefaultSearchQueryRewriteService().rewrite(input);

        assertEquals(input, result.originalQuery());
        assertEquals("Latest News", result.rewrittenQuery());
        assertNotSame(input, result.originalQuery());
        assertNotSame(input, result.rewrittenQuery());
        assertNotSame(result.originalQuery(), result.rewrittenQuery());
    }

    @Test
    void normalizationHandlesUnicodeWhitespaceAndPreservesNonWhitespaceCodePoints() {
        String input = "\u2003Java\t\n\r\u00a0: café café /v1\u202f/docs";

        SearchQuery result = new DefaultSearchQueryRewriteService().rewrite(input);

        assertEquals(input, result.originalQuery());
        assertEquals("Java : café café /v1 /docs", result.rewrittenQuery());
    }

    @Test
    void structuralNormalizationRemovesSupportedZeroWidthCharacters() {
        String input = "\uFEFFfoo\u200B\u200C\u200D\u2060bar\uFEFF";

        SearchQuery result = new DefaultSearchQueryRewriteService().rewrite(input);

        assertEquals(input, result.originalQuery());
        assertEquals("foobar", result.rewrittenQuery());
    }

    @Test
    void structuralNormalizationMapsOnlyFullWidthAsciiVariants() {
        String input = "Ａ１（test）！ ①";

        SearchQuery result = new DefaultSearchQueryRewriteService().rewrite(input);

        assertEquals("A1(test)! ①", result.rewrittenQuery());
    }

    @Test
    void structuralNormalizationComposesWithWhitespaceNormalization() {
        String input = "\uFEFF \tＡ\u200B  \u2060（test）\u3000";

        SearchQuery result = new DefaultSearchQueryRewriteService().rewrite(input);

        assertEquals(input, result.originalQuery());
        assertEquals("A (test)", result.rewrittenQuery());
    }

    @Test
    void normalizationDoesNotApplyUnicodeNormalizationForms() {
        String decomposed = "e\u0301";
        SearchQuery result = new DefaultSearchQueryRewriteService().rewrite("  " + decomposed + "  ");

        assertEquals(decomposed, result.rewrittenQuery());
    }

    @Test
    void structuralNormalizationIsIdempotent() {
        DefaultSearchQueryRewriteService service = new DefaultSearchQueryRewriteService();
        String input = "\uFEFF  Ａ\u200B  query\u2060  " ;

        String first = service.rewrite(input).rewrittenQuery();
        String second = service.rewrite(first).rewrittenQuery();

        assertEquals("A query", first);
        assertEquals(first, second);
    }

    @Test
    void whitespaceOnlyInputProducesEmptyCanonicalQuery() {
        SearchQuery result = new DefaultSearchQueryRewriteService().rewrite("\t\u2003\n");

        assertEquals("\t\u2003\n", result.originalQuery());
        assertEquals("", result.rewrittenQuery());
    }

    @Test
    void normalizationIsIdempotentAndCreatesFreshValues() {
        DefaultSearchQueryRewriteService service = new DefaultSearchQueryRewriteService();

        SearchQuery first = service.rewrite("  same\tquery  ");
        SearchQuery second = service.rewrite(first.rewrittenQuery());

        assertEquals(first.rewrittenQuery(), second.rewrittenQuery());
        assertNotSame(first, second);
        assertNotSame(first.rewrittenQuery(), second.rewrittenQuery());
        assertEquals("same query", first.rewrittenQuery());
        assertEquals(
                Arrays.stream(SearchQuery.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList(),
                Arrays.asList("originalQuery", "rewrittenQuery"));
    }

    @Test
    void nullInputIsRejected() {
        assertThrows(NullPointerException.class, () -> new DefaultSearchQueryRewriteService().rewrite(null));
    }
}