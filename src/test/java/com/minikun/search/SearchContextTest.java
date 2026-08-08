package com.minikun.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.minikun.pcs.SearchContext;
import com.minikun.search.model.SearchDecisionReason;
import org.junit.jupiter.api.Test;

class SearchContextTest {
    @Test
    void preservesExactQueryAndProvidesValueEquality() {
        String query = new String("  Latest\tNews  ");
        SearchContext first = new SearchContext(
                query, true, true, true, true, true, true,
                SearchDecisionReason.CURRENT_INFORMATION);
        SearchContext second = new SearchContext(
                new String(query), true, true, true, true, true, true,
                SearchDecisionReason.CURRENT_INFORMATION);

        assertEquals(query, first.userQuery());
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotSame(query, first.userQuery());
    }

    @Test
    void emptyContextIsEqualToEquivalentFreshValue() {
        SearchContext equivalent = new SearchContext(
                "", false, false, false, false, false, false,
                SearchDecisionReason.GENERAL_KNOWLEDGE);

        assertEquals(SearchContext.EMPTY, equivalent);
    }

    @Test
    void rejectsNullValueFields() {
        assertThrows(NullPointerException.class, () -> new SearchContext(
                null, false, false, false, false, false, false,
                SearchDecisionReason.GENERAL_KNOWLEDGE));
        assertThrows(NullPointerException.class, () -> new SearchContext(
                "query", false, false, false, false, false, false, null));
    }
}
