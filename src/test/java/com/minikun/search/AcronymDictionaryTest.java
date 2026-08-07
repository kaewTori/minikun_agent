package com.minikun.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.minikun.search.internal.ImmutableAcronymDictionary;
import java.util.List;
import org.junit.jupiter.api.Test;

class AcronymDictionaryTest {
    private final AcronymDictionary dictionary = new ImmutableAcronymDictionary();

    @Test
    void compileTimeMappingsPreserveDeclaredContentCardinalityAndOrder() {
        assertEquals(List.of("Java Development Kit"), dictionary.expansionsOf("JDK"));
        assertEquals(List.of("Continuous Integration", "CI pipeline"),
                dictionary.expansionsOf("CI"));
    }

    @Test
    void unknownQueriesReturnFreshImmutableEmptyLists() {
        List<String> first = dictionary.expansionsOf("unknown");
        List<String> second = dictionary.expansionsOf("unknown");

        assertEquals(List.of(), first);
        assertNotSame(first, second);
        assertThrows(UnsupportedOperationException.class, () -> first.add("value"));
    }

    @Test
    void knownQueriesReturnFreshListsAndFreshStringsDeterministically() {
        List<String> first = dictionary.expansionsOf("CI");
        List<String> second = dictionary.expansionsOf("CI");

        assertEquals(List.of("Continuous Integration", "CI pipeline"), first);
        assertEquals(first, second);
        assertNotSame(first, second);
        assertNotSame(first.get(0), second.get(0));
        assertNotSame(first.get(1), second.get(1));
        assertThrows(UnsupportedOperationException.class, () -> first.add("value"));
    }

    @Test
    void matchingUsesExactCanonicalStringEquality() {
        assertEquals(List.of(), dictionary.expansionsOf("jdk"));
        assertEquals(List.of(), dictionary.expansionsOf("JDK "));
    }
}
