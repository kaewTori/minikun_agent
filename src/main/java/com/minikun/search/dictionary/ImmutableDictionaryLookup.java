package com.minikun.search.dictionary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared copy semantics for the small, immutable search dictionaries. */
final class ImmutableDictionaryLookup {
    private ImmutableDictionaryLookup() {
    }

    static List<String> copyValues(List<String> values) {
        List<String> copy = new ArrayList<>(values == null ? 0 : values.size());
        if (values != null) {
            for (String value : values) {
                copy.add(new String(value));
            }
        }
        return Collections.unmodifiableList(copy);
    }
}
