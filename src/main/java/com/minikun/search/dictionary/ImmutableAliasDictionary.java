package com.minikun.search.dictionary;

import com.minikun.search.AliasDictionary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ImmutableAliasDictionary implements AliasDictionary {
    private static final Map<String, List<String>> ALIASES = Map.of(
            "postgres", List.of("postgresql"),
            "wildfly", List.of("jboss"),
            "flip3", List.of("galaxy z flip3"));

    @Override
    public List<String> aliasesOf(String canonicalQuery) {
        Objects.requireNonNull(canonicalQuery, "canonical query must not be null");
        List<String> aliases = ALIASES.getOrDefault(canonicalQuery, List.of());
        List<String> freshAliases = new ArrayList<>(aliases.size());
        for (String alias : aliases) {
            freshAliases.add(new String(alias));
        }
        return Collections.unmodifiableList(freshAliases);
    }
}