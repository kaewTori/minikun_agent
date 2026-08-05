package com.minikun.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.minikun.search.internal.DefaultSearchQueryRewriteService;
import com.minikun.search.model.SearchQuery;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class QueryRewriteTest {
    @Test
    void identityRewritePreservesExactInputAndCreatesIndependentValues() {
        String input = new String("  Latest   News  ");
        SearchQuery result = new DefaultSearchQueryRewriteService().rewrite(input);

        assertEquals(input, result.originalQuery());
        assertEquals(input, result.rewrittenQuery());
        assertNotSame(input, result.originalQuery());
        assertNotSame(input, result.rewrittenQuery());
        assertNotSame(result.originalQuery(), result.rewrittenQuery());
    }

    @Test
    void identityRewriteCreatesNewImmutableQueryForEveryInvocation() {
        DefaultSearchQueryRewriteService service = new DefaultSearchQueryRewriteService();

        SearchQuery first = service.rewrite("same");
        SearchQuery second = service.rewrite("same");

        assertEquals(first, second);
        assertNotSame(first, second);
        assertNotSame(first.originalQuery(), second.originalQuery());
        assertNotSame(first.rewrittenQuery(), second.rewrittenQuery());
        assertEquals(
                Arrays.stream(SearchQuery.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList(),
                Arrays.asList("originalQuery", "rewrittenQuery"));
    }
}