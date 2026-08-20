package com.minikun.knowledge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Application-owned filesystem boundary for personal knowledge ingestion. */
public record KnowledgeRoot(String name, Path path) {
    public KnowledgeRoot {
        if (name == null || !name.matches("[a-zA-Z0-9_-]{1,40}")) {
            throw new IllegalArgumentException("knowledge root name must contain only letters, numbers, _ or -");
        }
        if (path == null || !path.isAbsolute()) {
            throw new IllegalArgumentException("knowledge root path must be absolute");
        }
        name = name.toLowerCase(Locale.ROOT);
        path = path.normalize();
    }

    public static List<KnowledgeRoot> parseList(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<KnowledgeRoot> result = new ArrayList<>();
        for (String entry : value.split(",")) {
            String[] fields = entry.trim().split("=", 2);
            if (fields.length != 2 || fields[0].isBlank() || fields[1].isBlank()) {
                throw new IllegalArgumentException("knowledge roots must use name=/absolute/path format");
            }
            result.add(new KnowledgeRoot(fields[0].trim(), Path.of(fields[1].trim())));
        }
        if (result.stream().map(KnowledgeRoot::name).distinct().count() != result.size()) {
            throw new IllegalArgumentException("knowledge root names must be unique");
        }
        return List.copyOf(result);
    }
}
