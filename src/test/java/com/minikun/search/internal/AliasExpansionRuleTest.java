package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.minikun.search.AliasDictionary;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AliasExpansionRuleTest {
    @Test
    void invokesDictionaryExactlyOnceWithCanonicalInputAndReturnsItsOutput() {
        List<String> aliases = List.of("alternate");
        AtomicInteger calls = new AtomicInteger();
        AliasDictionary dictionary = query -> {
            calls.incrementAndGet();
            assertEquals("canonical query", query);
            return aliases;
        };

        List<String> result = new AliasExpansionRule(dictionary).expand("canonical query");

        assertEquals(1, calls.get());
        assertSame(aliases, result);
    }

    @Test
    void doesNotNormalizeAggregateDeduplicateOrMutateDictionaryOutput() {
        List<String> aliases = new ArrayList<>(List.of("one  value", "one  value", "canonical"));
        AliasDictionary dictionary = query -> aliases;

        List<String> result = new AliasExpansionRule(dictionary).expand("  canonical  ");

        assertSame(aliases, result);
        assertEquals(List.of("one  value", "one  value", "canonical"), aliases);
    }

    @Test
    void dependsOnlyOnAliasDictionaryAndRejectsNulls() {
        assertThrows(NullPointerException.class, () -> new AliasExpansionRule(null));
        assertThrows(NullPointerException.class,
                () -> new AliasExpansionRule(query -> List.of()).expand(null));
    }
}
