package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.minikun.search.SynonymDictionary;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SynonymExpansionRuleTest {
    @Test
    void invokesDictionaryExactlyOnceAndReturnsItsListUnchanged() {
        List<String> synonyms = List.of("alternate");
        AtomicInteger calls = new AtomicInteger();
        SynonymDictionary dictionary = query -> {
            calls.incrementAndGet();
            assertEquals("Exact Canonical", query);
            return synonyms;
        };

        List<String> result = new SynonymExpansionRule(dictionary).expand("Exact Canonical");

        assertEquals(1, calls.get());
        assertSame(synonyms, result);
    }

    @Test
    void preservesUnknownAndMultipleDictionaryValuesWithoutRewriting() {
        List<String> values = new ArrayList<>(List.of("One  Value", "ONE value", "canonical"));
        SynonymDictionary dictionary = query -> values;

        List<String> result = new SynonymExpansionRule(dictionary).expand("  Canonical  ");

        assertEquals(values, result);
        assertSame(values, result);
    }

    @Test
    void neverMutatesDictionaryOutput() {
        List<String> values = new ArrayList<>(List.of("first", "first", "canonical"));
        SynonymDictionary dictionary = query -> values;

        new SynonymExpansionRule(dictionary).expand("canonical");

        assertEquals(List.of("first", "first", "canonical"), values);
    }

    @Test
    void rejectsNullDependenciesAndInput() {
        assertThrows(NullPointerException.class, () -> new SynonymExpansionRule(null));
        assertThrows(NullPointerException.class,
                () -> new SynonymExpansionRule(query -> List.of()).expand(null));
    }
}