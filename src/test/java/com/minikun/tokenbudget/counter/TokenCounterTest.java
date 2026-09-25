package com.minikun.tokenbudget.counter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenCounterTest {
    private final TokenCounter counter = new ApproximateTokenCounter();

    @Test
    void rejectsNullText() {
        assertThrows(NullPointerException.class, () -> counter.count(null));
    }

    @Test
    void countsEmptyTextAsZeroTokens() {
        assertEquals(0, counter.count(""));
    }

    @Test
    void estimatesFortyCharactersAsTenTokens() {
        assertEquals(10, counter.count("1234567890123456789012345678901234567890"));
    }

    @Test
    void givesThaiTextMoreTokensThanAsciiWithoutUsingFullUtf8ByteCost() {
        assertEquals(9, counter.count("สวัสดี".repeat(4)));
    }

    @Test
    void accountsForCodeAndJsonSyntax() {
        assertEquals(7, counter.count("{\"role\":\"user\"}"));
        assertEquals(99, counter.count(("public static int sum(int[] values) { int total = 0; "
                + "for (int value : values) total += value; return total; } ").repeat(3)));
    }

    @Test
    void roundsShortTextUpToOneToken() {
        assertEquals(1, counter.count("abc"));
    }

    @Test
    void producesDeterministicResults() {
        String text = "deterministic token estimation input";

        assertEquals(counter.count(text), counter.count(text));
    }
}
