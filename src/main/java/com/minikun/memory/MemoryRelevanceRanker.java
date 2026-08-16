package com.minikun.memory;

import com.minikun.memory.model.Memory;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Lightweight local relevance ranking for a home agent; no embedding call per request. */
public final class MemoryRelevanceRanker {
    private MemoryRelevanceRanker() { }
    public static List<Memory> rank(List<Memory> memories, String query, int limit) {
        if (memories == null || memories.isEmpty() || limit == 0) return List.of();
        Set<String> terms = terms(query);
        return memories.stream()
                .sorted(Comparator.comparingDouble((Memory memory) -> score(memory, terms))
                        .reversed().thenComparing(Memory::createdAt, Comparator.reverseOrder()))
                .limit(limit).toList();
    }
    private static double score(Memory memory, Set<String> terms) {
        if (terms.isEmpty()) return memory.confidence() * .3;
        String text = (memory.category().name() + " " + memory.content()).toLowerCase(Locale.ROOT);
        long matches = terms.stream().filter(text::contains).count();
        return (double) matches / terms.size() * .7 + memory.confidence() * .2;
    }
    private static Set<String> terms(String query) {
        if (query == null) return Set.of();
        return java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(term -> term.length() > 1).collect(Collectors.toUnmodifiableSet());
    }
}
