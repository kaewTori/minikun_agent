package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.minikun.search.ExpansionRule;
import com.minikun.search.AliasDictionary;
import com.minikun.search.model.ExpandedSearchQuery;
import com.minikun.search.model.SearchQuery;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleBasedSearchQueryExpansionServiceTest {
    @Test
    void rulesReceiveOnlyCanonicalInputAndRemainIsolated() {
        String canonical = new String("canonical");
        List<String> firstInputs = new ArrayList<>();
        List<String> secondInputs = new ArrayList<>();
        ExpansionRule first = query -> {
            firstInputs.add(query);
            return List.of(new String("first"));
        };
        ExpansionRule second = query -> {
            secondInputs.add(query);
            return List.of(new String("second"));
        };

        ExpandedSearchQuery result = new RuleBasedSearchQueryExpansionService(List.of(first, second))
                .expand(new SearchQuery("original", canonical));

        assertEquals(List.of("canonical", "first", "second"), result.expandedQueries());
        assertEquals(List.of(canonical), firstInputs);
        assertEquals(List.of(canonical), secondInputs);
        assertSame(canonical, firstInputs.get(0));
        assertSame(canonical, secondInputs.get(0));
    }

    @Test
    void removesDuplicateSynonymsAndCanonicalCollisionByStringContentOnly() {
        String canonical = new String("canonical");
        ExpansionRule rule = query -> List.of(
                new String("synonym"), new String("synonym"), new String("canonical"));

        ExpandedSearchQuery result = new RuleBasedSearchQueryExpansionService(List.of(rule))
                .expand(new SearchQuery("original", canonical));

        assertEquals(List.of("canonical", "synonym"), result.expandedQueries());
        assertEquals("canonical", result.rewrittenQuery());
        assertThrows(UnsupportedOperationException.class,
                () -> result.expandedQueries().add("other"));
    }

        @Test
        void aggregatesSynonymThenAcronymOutputsAndRemovesCrossRuleDuplicatesOnce() {
        ExpansionRule synonym = query -> List.of(
            new String("synonym"), new String("shared"));
        ExpansionRule acronym = query -> List.of(
            new String("shared"), new String("acronym"), new String("canonical"));

        ExpandedSearchQuery result = new RuleBasedSearchQueryExpansionService(
            List.of(synonym, acronym)).expand(new SearchQuery("original", "canonical"));

        assertEquals(List.of("canonical", "synonym", "shared", "acronym"),
            result.expandedQueries());
        }

        @Test
        void repeatedExecutionIsEqualRegardlessOfRuleOutputObjectIdentity() {
        ExpansionRule acronym = query -> List.of(new String("first"), new String("second"));
        RuleBasedSearchQueryExpansionService service =
            new RuleBasedSearchQueryExpansionService(List.of(acronym));

        ExpandedSearchQuery first = service.expand(new SearchQuery("original", "canonical"));
        ExpandedSearchQuery second = service.expand(new SearchQuery("original", "canonical"));

        assertEquals(first, second);
        }

        @Test
        void aliasOutputIsAggregatedWithCrossRuleDuplicatesRemovedByService() {
        AliasDictionary dictionary = query -> List.of(
            new String("canonical"), new String("alias"), new String("alias"));
        ExpansionRule synonym = query -> List.of(new String("alias"), new String("synonym"));

        ExpandedSearchQuery result = new RuleBasedSearchQueryExpansionService(List.of(
            synonym, new AliasExpansionRule(dictionary)))
            .expand(new SearchQuery("original", "canonical"));

        assertEquals(List.of("canonical", "alias", "synonym"), result.expandedQueries());
        }

        @Test
        void unknownAliasLeavesSprintF53ExpansionOutputUnchanged() {
        ExpansionRule aliasRule = new AliasExpansionRule(query -> List.of());
        ExpandedSearchQuery withoutAlias = new RuleBasedSearchQueryExpansionService(List.<ExpansionRule>of(
            new IdentityExpansionRule(), query -> List.of("synonym"), query -> List.of("acronym")))
            .expand(new SearchQuery("original", "unknown"));
        ExpandedSearchQuery withAlias = new RuleBasedSearchQueryExpansionService(List.<ExpansionRule>of(
            new IdentityExpansionRule(), query -> List.of("synonym"), query -> List.of("acronym"),
            aliasRule))
            .expand(new SearchQuery("original", "unknown"));

        assertEquals(withoutAlias, withAlias);
        }
}