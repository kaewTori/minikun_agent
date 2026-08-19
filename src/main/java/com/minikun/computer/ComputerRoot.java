package com.minikun.computer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One application-owned filesystem boundary exposed by a short logical name. */
public record ComputerRoot(String name, Path path) {
    public ComputerRoot {
        if (name == null || !name.matches("[a-zA-Z0-9_-]{1,40}")) {
            throw new IllegalArgumentException("computer root name must contain only letters, numbers, _ or -");
        }
        if (path == null || !path.isAbsolute()) {
            throw new IllegalArgumentException("computer root path must be absolute");
        }
        name = name.toLowerCase(Locale.ROOT);
        path = path.normalize();
    }

    static List<ComputerRoot> parseList(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<ComputerRoot> result = new ArrayList<>();
        for (String entry : value.split(",")) {
            String[] fields = entry.trim().split("=", 2);
            if (fields.length != 2 || fields[0].isBlank() || fields[1].isBlank()) {
                throw new IllegalArgumentException("computer roots must use name=/absolute/path format");
            }
            result.add(new ComputerRoot(fields[0].trim(), Path.of(fields[1].trim())));
        }
        long distinct = result.stream().map(ComputerRoot::name).distinct().count();
        if (distinct != result.size()) throw new IllegalArgumentException("computer root names must be unique");
        return List.copyOf(result);
    }
}
