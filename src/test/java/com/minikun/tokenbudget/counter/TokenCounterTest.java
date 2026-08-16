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
    void roundsShortTextUpToOneToken() {
        assertEquals(1, counter.count("abc"));
    }

    @Test
    void producesDeterministicResults() {
        String text = "deterministic token estimation input";

        assertEquals(counter.count(text), counter.count(text));
    }
}
