package com.minikun.search.internal;

import com.minikun.search.SynonymDictionary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ImmutableSynonymDictionary implements SynonymDictionary {
    private static final Map<String, List<String>> SYNONYMS = Map.of(
            "latest Java", List.of("current Java", "Java platform"));

    @Override
    public List<String> synonymsOf(String canonicalQuery) {
        Objects.requireNonNull(canonicalQuery, "canonical query must not be null");
        List<String> synonyms = SYNONYMS.getOrDefault(canonicalQuery, List.of());
        List<String> freshSynonyms = new ArrayList<>(synonyms.size());
        for (String synonym : synonyms) {
            freshSynonyms.add(new String(synonym));
        }
        return Collections.unmodifiableList(freshSynonyms);
    }
}