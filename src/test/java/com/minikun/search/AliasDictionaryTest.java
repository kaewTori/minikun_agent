package com.minikun.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.minikun.search.dictionary.ImmutableAliasDictionary;
import java.util.List;
import org.junit.jupiter.api.Test;

class AliasDictionaryTest {
    private final AliasDictionary dictionary = new ImmutableAliasDictionary();

    @Test
    void knownQueriesPreserveExactMappingsAndCardinality() {
        assertEquals(List.of("postgresql"), dictionary.aliasesOf("postgres"));
        assertEquals(List.of("jboss"), dictionary.aliasesOf("wildfly"));
        assertEquals(List.of("galaxy z flip3"), dictionary.aliasesOf("flip3"));
    }

    @Test
    void unknownAndNonExactQueriesReturnFreshImmutableEmptyLists() {
        List<String> first = dictionary.aliasesOf("unknown");
        List<String> second = dictionary.aliasesOf("Postgres");

        assertEquals(List.of(), first);
        assertEquals(List.of(), second);
        assertNotSame(first, second);
        assertThrows(UnsupportedOperationException.class, () -> first.add("alias"));
    }

    @Test
    void repeatedKnownQueriesReturnFreshListsAndStrings() {
        List<String> first = dictionary.aliasesOf("postgres");
        List<String> second = dictionary.aliasesOf("postgres");

        assertEquals(first, second);
        assertNotSame(first, second);
        assertNotSame(first.get(0), second.get(0));
        assertThrows(UnsupportedOperationException.class, () -> first.add("alias"));
    }

    @Test
    void matchingDoesNotNormalizeOrPerformSubstringMatching() {
        assertEquals(List.of(), dictionary.aliasesOf(" postgres"));
        assertEquals(List.of(), dictionary.aliasesOf("postgres "));
        assertEquals(List.of(), dictionary.aliasesOf("my postgres query"));
    }

    @Test
    void rejectsNullCanonicalQuery() {
        assertThrows(NullPointerException.class, () -> dictionary.aliasesOf(null));
    }
}
