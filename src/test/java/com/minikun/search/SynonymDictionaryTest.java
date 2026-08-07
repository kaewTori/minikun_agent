package com.minikun.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.minikun.search.internal.ImmutableSynonymDictionary;
import java.util.List;
import org.junit.jupiter.api.Test;

class SynonymDictionaryTest {
    private final SynonymDictionary dictionary = new ImmutableSynonymDictionary();

    @Test
    void knownQueryPreservesDeclarationOrderAndReturnsMultipleSynonyms() {
        assertEquals(List.of("current Java", "Java platform"), dictionary.synonymsOf("latest Java"));
    }

    @Test
    void unknownQueryReturnsFreshImmutableEmptyLists() {
        List<String> first = dictionary.synonymsOf("unknown query");
        List<String> second = dictionary.synonymsOf("unknown query");

        assertEquals(List.of(), first);
        assertNotSame(first, second);
        assertThrows(UnsupportedOperationException.class, () -> first.add("value"));
    }

    @Test
    void repeatedKnownQueriesReturnFreshListsAndFreshStringsWithEqualContent() {
        List<String> first = dictionary.synonymsOf("latest Java");
        List<String> second = dictionary.synonymsOf("latest Java");

        assertEquals(first, second);
        assertNotSame(first, second);
        assertNotSame(first.get(0), second.get(0));
        assertNotSame(first.get(1), second.get(1));
        assertThrows(UnsupportedOperationException.class, () -> first.add("value"));
    }

    @Test
    void matchingUsesExactCanonicalStringEquality() {
        assertEquals(List.of(), dictionary.synonymsOf("Latest Java"));
        assertEquals(List.of(), dictionary.synonymsOf("latest Java "));
    }
}