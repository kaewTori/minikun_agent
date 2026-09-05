package com.minikun.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.minikun.search.internal.RuleBasedSearchQueryExpansionService;
import com.minikun.search.model.ExpandedSearchQuery;
import com.minikun.search.model.SearchQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
    void noRulesPreserveTheCanonicalQuery() {
        RuleBasedSearchQueryExpansionService service =
                new RuleBasedSearchQueryExpansionService(List.of());
        SearchQuery input = new SearchQuery("original", "canonical");

        ExpandedSearchQuery first = service.expand(input);

        assertEquals(new ExpandedSearchQuery("original", "canonical", List.of("canonical")), first);
        assertEquals(List.of("canonical"), first.expandedQueries());
    }

    @Test
    void rulesReceiveOnlyCanonicalQueryAndRunInOrder() {
        String canonical = new String("canonical");
        SearchQuery input = new SearchQuery("original", canonical);
        List<String> observedInputs = new ArrayList<>();
        AtomicInteger firstRuleCalls = new AtomicInteger();
        AtomicInteger secondRuleCalls = new AtomicInteger();

        ExpansionRule firstRule = rewrittenQuery -> {
            firstRuleCalls.incrementAndGet();
            observedInputs.add(rewrittenQuery);
            return List.of(new String("alternate"), new String("canonical"), "first");
        };
        ExpansionRule secondRule = rewrittenQuery -> {
            secondRuleCalls.incrementAndGet();
            observedInputs.add(rewrittenQuery);
            return List.of(new String("alternate"), new String("second"), "first");
        };

        ExpandedSearchQuery result = new RuleBasedSearchQueryExpansionService(
                List.of(firstRule, secondRule)).expand(input);

        assertEquals(List.of("canonical", "alternate", "first", "second"),
                result.expandedQueries());
        assertEquals(List.of(canonical, canonical), observedInputs);
        assertEquals(1, firstRuleCalls.get());
        assertEquals(1, secondRuleCalls.get());
        assertEquals(canonical, observedInputs.get(0));
        assertEquals(canonical, observedInputs.get(1));
    }

    @Test
    void emptyRuleOutputPreservesCanonicalAndRepeatedExpansionIsDeterministic() {
        SearchQuery input = new SearchQuery("original", "canonical");
        RuleBasedSearchQueryExpansionService service =
                new RuleBasedSearchQueryExpansionService(List.of(query -> List.of()));

        ExpandedSearchQuery first = service.expand(input);
        ExpandedSearchQuery second = service.expand(input);

        assertEquals(new ExpandedSearchQuery("original", "canonical", List.of("canonical")), first);
        assertEquals(first, second);
        assertEquals(List.of("canonical"), first.expandedQueries());
    }
}
