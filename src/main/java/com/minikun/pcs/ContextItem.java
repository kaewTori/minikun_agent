package com.minikun.pcs;

import java.util.Objects;

public record ContextItem(
        ContextBudgetSection section,
        String content,
        int priority,
        boolean required) {
    public ContextItem {
        Objects.requireNonNull(section, "section must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (priority < 0) {
            throw new IllegalArgumentException("priority must not be negative");
        }
        content = new String(content);
    }

    public int size() {
        return content.length();
    }
}