package com.minikun.tokenbudget.counter;

import java.util.Objects;

public final class ApproximateTokenCounter implements TokenCounter {
    private static final int APPROXIMATE_CHARACTERS_PER_TOKEN = 4;

    @Override
    public long count(String text) {
        Objects.requireNonNull(text, "text must not be null");
        return text.length() / APPROXIMATE_CHARACTERS_PER_TOKEN;
    }
}
