package com.minikun.context.selection;

import com.minikun.pcs.KnowledgeSource;

import java.util.Map;
import java.util.Objects;

/** Higher values are retained longer when optional context is selected. */
public final class DefaultContextSourcePriorityPolicy implements ContextSourcePriorityPolicy {
    private static final Map<KnowledgeSource, Integer> DEFAULT_PRIORITIES = Map.of(
            KnowledgeSource.MEMORY, 3,
            KnowledgeSource.SEARCH, 2,
            KnowledgeSource.BROWSER, 1);

    private final Map<KnowledgeSource, Integer> priorities;

    public DefaultContextSourcePriorityPolicy() {
        this(DEFAULT_PRIORITIES);
    }

    public DefaultContextSourcePriorityPolicy(Map<KnowledgeSource, Integer> priorities) {
        Objects.requireNonNull(priorities, "priorities must not be null");
        for (KnowledgeSource source : KnowledgeSource.values()) {
            Integer priority = priorities.get(source);
            if (priority == null || priority < 0) {
                throw new IllegalArgumentException("priority must be defined and non-negative: " + source);
            }
        }
        this.priorities = Map.copyOf(priorities);
    }

    @Override
    public int priority(KnowledgeSource source) {
        return priorities.get(Objects.requireNonNull(source, "source must not be null"));
    }
}
