package com.minikun.search.internal;

import com.minikun.search.AcronymDictionary;
import java.util.ArrayList;
import java.util.Collections;
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
        List<String> expansions = ACRONYM_EXPANSIONS.getOrDefault(canonicalQuery, List.of());
        List<String> freshExpansions = new ArrayList<>(expansions.size());
        for (String expansion : expansions) {
            freshExpansions.add(new String(expansion));
        }
        return Collections.unmodifiableList(freshExpansions);
    }
}
