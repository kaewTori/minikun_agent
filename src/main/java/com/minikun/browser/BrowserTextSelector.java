package com.minikun.browser;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Selects query-matching excerpts before applying the prompt's character budget. */
final class BrowserTextSelector {
    static String select(String text, String query, int limit) {
        if (text.length() <= limit) return text;
        if (limit < 8) {
            int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
            return text.substring(0, end);
        }
        Set<String> terms = terms(query);
        int chunkSize = Math.min(1_000, Math.max(1, limit / 4));
        List<Chunk> chunks = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(text.length(), start + chunkSize);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end++;
            String part = text.substring(start, end);
            String lower = part.toLowerCase(Locale.ROOT);
            int score = (int) terms.stream().filter(lower::contains).count();
            chunks.add(new Chunk(chunks.size(), part, score));
            start = end;
        }
        // ponytail: lexical matching handles Thai/English without embeddings; replace if relevance evaluation proves inadequate.
        List<Chunk> ranked = new ArrayList<>(chunks);
        ranked.sort(Comparator.comparingInt(Chunk::score).reversed().thenComparingInt(Chunk::index));
        Set<Integer> selected = new java.util.TreeSet<>();
        selected.add(0);
        int remaining = limit - chunks.get(0).text().length();
        List<Chunk> priorities = new ArrayList<>();
        ranked.stream().filter(chunk -> chunk.score() > 0).forEach(priorities::add);
        priorities.add(chunks.get(chunks.size() - 1));
        priorities.add(chunks.get(chunks.size() / 2));
        priorities.addAll(ranked);
        for (Chunk chunk : priorities) {
            if (!selected.contains(chunk.index()) && chunk.text().length() + 7 <= remaining) {
                selected.add(chunk.index());
                remaining -= chunk.text().length() + 7;
            }
        }
        return String.join("\n[...]\n", selected.stream().map(index -> chunks.get(index).text()).toList());
    }

    private static Set<String> terms(String query) {
        String clean = query == null ? "" : query.replaceAll("https?://\\S+", "").toLowerCase(Locale.ROOT);
        if (clean.length() > 5_000) clean = clean.substring(0, 5_000);
        Set<String> terms = new LinkedHashSet<>();
        BreakIterator words = BreakIterator.getWordInstance(Locale.forLanguageTag("th"));
        words.setText(clean);
        for (int start = words.first(), end = words.next(); end != BreakIterator.DONE; start = end, end = words.next()) {
            String term = clean.substring(start, end).strip();
            if (term.length() > 1 && term.codePoints().anyMatch(Character::isLetterOrDigit)
                    && !Set.of("the", "and", "for", "this", "that", "with", "จาก", "ของ", "และ", "ให้", "อ่าน", "สรุป").contains(term)) {
                terms.add(term);
                if (terms.size() == 64) break;
            }
        }
        return terms;
    }

    private record Chunk(int index, String text, int score) { }
}
