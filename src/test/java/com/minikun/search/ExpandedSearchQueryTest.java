package com.minikun.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.minikun.search.internal.DefaultSearchQueryExpansionService;
import com.minikun.search.model.ExpandedSearchQuery;
import com.minikun.search.model.SearchQuery;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpandedSearchQueryTest {
    @Test
    void preservesValuesAndOrderWithAnImmutableCopy() {
        List<String> source = new ArrayList<>(List.of("canonical", "alternate"));
        ExpandedSearchQuery query = new ExpandedSearchQuery("original", "canonical", source);
        source.set(0, "changed");

        assertEquals("original", query.originalQuery());
        assertEquals("canonical", query.rewrittenQuery());
        assertEquals(List.of("canonical", "alternate"), query.expandedQueries());
        assertThrows(UnsupportedOperationException.class,
                () -> query.expandedQueries().add("third"));
    }

    @Test
    void enforcesNonEmptyFirstCanonicalAndNullFreeInvariants() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExpandedSearchQuery("original", "canonical", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ExpandedSearchQuery("original", "canonical", List.of("alternate")));
        assertThrows(NullPointerException.class,
                () -> new ExpandedSearchQuery("original", "canonical", List.of("canonical", null)));
    }

    @Test
    void identityExpansionReturnsOneFreshQueryPerInvocation() {
        DefaultSearchQueryExpansionService service = new DefaultSearchQueryExpansionService();
        SearchQuery input = new SearchQuery("original", "canonical");

        ExpandedSearchQuery first = service.expand(input);
        ExpandedSearchQuery second = service.expand(input);

        assertEquals(new ExpandedSearchQuery("original", "canonical", List.of("canonical")), first);
        assertEquals(List.of("canonical"), first.expandedQueries());
        assertNotSame(first, second);
        assertNotSame(first.expandedQueries(), second.expandedQueries());
        assertNotSame(first.originalQuery(), second.originalQuery());
        assertNotSame(first.rewrittenQuery(), second.rewrittenQuery());
        assertNotSame(first.expandedQueries().get(0), second.expandedQueries().get(0));
    }
}