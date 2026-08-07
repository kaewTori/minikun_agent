package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.minikun.search.AcronymDictionary;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AcronymExpansionRuleTest {
    @Test
    void invokesDictionaryExactlyOnceWithCanonicalInputAndReturnsItsListUnchanged() {
        List<String> expansions = List.of("Java Development Kit");
        AtomicInteger calls = new AtomicInteger();
        AcronymDictionary dictionary = query -> {
            calls.incrementAndGet();
            assertEquals("JDK", query);
            return expansions;
        };

        List<String> result = new AcronymExpansionRule(dictionary).expand("JDK");

        assertEquals(1, calls.get());
        assertSame(expansions, result);
    }

    @Test
    void preservesUnknownAndMultipleValuesWithoutMutation() {
        List<String> values = new ArrayList<>(List.of("first", "first", "canonical"));
        AcronymDictionary dictionary = query -> values;

        List<String> result = new AcronymExpansionRule(dictionary).expand("canonical");

        assertSame(values, result);
        assertEquals(List.of("first", "first", "canonical"), values);
    }

    @Test
    void rejectsNullDependenciesAndInput() {
        assertThrows(NullPointerException.class, () -> new AcronymExpansionRule(null));
        assertThrows(NullPointerException.class,
                () -> new AcronymExpansionRule(query -> List.of()).expand(null));
    }
}
