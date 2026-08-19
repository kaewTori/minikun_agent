package com.minikun.search.dictionary;

import com.minikun.search.AcronymDictionary;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ImmutableAcronymDictionary implements AcronymDictionary {
    private static final Map<String, List<String>> ACRONYM_EXPANSIONS = Map.of(
            "JDK", List.of("Java Development Kit"),
            "CI", List.of("Continuous Integration", "CI pipeline"));

    @Override
    public List<String> expansionsOf(String canonicalQuery) {
        Objects.requireNonNull(canonicalQuery, "canonical query must not be null");
        return ImmutableDictionaryLookup.copyValues(ACRONYM_EXPANSIONS.get(canonicalQuery));
    }
}
