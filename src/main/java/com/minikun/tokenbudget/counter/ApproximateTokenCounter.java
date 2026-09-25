package com.minikun.tokenbudget.counter;

import java.util.Objects;

public final class ApproximateTokenCounter implements TokenCounter {
    // ponytail: weighted characters approximate the current model; use its tokenizer if usage drift matters.
    private static final String STRUCTURAL = "{}[]();:=<>\"'";

    @Override
    public long count(String text) {
        Objects.requireNonNull(text, "text must not be null");
        long ascii = text.chars().filter(character -> character < 128).count();
        long syntax = text.chars().filter(character -> STRUCTURAL.indexOf(character) >= 0).count();
        long syntaxPremium = syntax * 10 >= text.length() ? syntax * 3 : 0;
        long eighths = ascii * 2 + (text.length() - ascii) * 3 + syntaxPremium;
        return (eighths + 7) / 8;
    }
}
