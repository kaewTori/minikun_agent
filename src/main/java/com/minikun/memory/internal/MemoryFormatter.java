package com.minikun.memory.internal;

import java.util.List;

import com.minikun.memory.model.Memory;
import com.minikun.pcs.model.KnowledgeContext;

final class MemoryFormatter {
    private final int maximumCharacters;

    MemoryFormatter(int maximumCharacters) {
        if (maximumCharacters < 1) {
            throw new IllegalArgumentException("maximum knowledge characters must be positive");
        }
        this.maximumCharacters = maximumCharacters;
    }

    KnowledgeContext format(List<Memory> memories) {
        if (memories == null || memories.isEmpty()) {
            return new KnowledgeContext("");
        }

        StringBuilder content = new StringBuilder();
        for (Memory memory : memories) {
            String entry = memory.category().name() + ": " + memory.content();
            int separatorLength = content.isEmpty() ? 0 : 1;
            if (content.length() + separatorLength + entry.length() > maximumCharacters) {
                break;
            }
            if (!content.isEmpty()) {
                content.append('\n');
            }
            content.append(entry);
        }
        return new KnowledgeContext(content.toString());
    }
}