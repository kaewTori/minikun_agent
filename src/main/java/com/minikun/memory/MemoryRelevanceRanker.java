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
    public static Set<String> terms(String query) {
        if (query == null || query.isBlank()) return Set.of();
        String normalized = query.toLowerCase(Locale.ROOT);
        java.text.BreakIterator words = java.text.BreakIterator.getWordInstance(Locale.forLanguageTag("th"));
        words.setText(normalized);
        Set<String> result = new java.util.LinkedHashSet<>();
        int start = words.first();
        for (int end = words.next(); end != java.text.BreakIterator.DONE; start = end, end = words.next()) {
            String word = normalized.substring(start, end).strip();
            if (word.length() > 1 && word.codePoints().anyMatch(Character::isLetterOrDigit)) result.add(word);
            if (result.size() == 24) break;
        }
        return java.util.Collections.unmodifiableSet(result);
    }
}
