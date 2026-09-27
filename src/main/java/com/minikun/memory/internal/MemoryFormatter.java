package com.minikun.memory.internal;

import java.util.List;

import com.minikun.memory.model.Memory;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
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
        List<KnowledgeCandidate> candidates = new java.util.ArrayList<>();
        for (int index = 0; index < memories.size(); index++) {
            Memory memory = memories.get(index);
            String entry = memory.category().name()
                    + ": " + memory.content()
                    + (memory.fact() == null ? " [legacy: validity unknown]" : " [subject=" + memory.fact().subject()
                        + ", key=" + memory.fact().key() + ", value=" + memory.fact().value()
                        + ", validFrom=" + memory.fact().validFrom() + ", validTo=" + memory.fact().validTo()
                        + ", recordedAt=" + memory.fact().recordedAt() + ", evidence=" + memory.fact().evidence() + "]")
                    + " (confidence=" + memory.confidence()
                    + ", source=" + memory.source()
                    + ", memoryId=" + memory.id().value()
                    + ", conversationId=" + (memory.conversationId() == null ? "unknown" : memory.conversationId())
                    + ", reason=" + memory.reason() + ")";
            int separatorLength = content.isEmpty() ? 0 : 1;
            if (content.length() + separatorLength + entry.length() > maximumCharacters) {
                break;
            }
            if (!content.isEmpty()) {
                content.append('\n');
            }
            content.append(entry);
            candidates.add(new KnowledgeCandidate(
                    "memory-" + memory.id().value(), KnowledgeSource.MEMORY, entry, index,
                    memory.conversationId() == null ? "memory" : "conversation:" + memory.conversationId()));
        }
        return new KnowledgeContext(content.toString(), candidates);
    }
}
