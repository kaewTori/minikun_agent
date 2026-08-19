package com.minikun.search.dictionary;

import com.minikun.search.SynonymDictionary;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ImmutableSynonymDictionary implements SynonymDictionary {
    private static final Map<String, List<String>> SYNONYMS = Map.of(
            "latest Java", List.of("current Java", "Java platform"));

    @Override
    public List<String> synonymsOf(String canonicalQuery) {
        Objects.requireNonNull(canonicalQuery, "canonical query must not be null");
        return ImmutableDictionaryLookup.copyValues(SYNONYMS.get(canonicalQuery));
    }
}
