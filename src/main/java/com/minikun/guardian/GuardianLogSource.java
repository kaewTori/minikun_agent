package com.minikun.guardian;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

record GuardianLogSource(String name, Path path) {
    GuardianLogSource {
        if (name == null || name.isBlank() || path == null) {
            throw new IllegalArgumentException("guardian log source requires name and path");
        }
        name = name.trim();
        path = path.toAbsolutePath().normalize();
    }

    static List<GuardianLogSource> parseList(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<GuardianLogSource> result = new ArrayList<>();
        for (String entry : value.split(",")) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalArgumentException("guardian log sources must use name=path");
            }
            result.add(new GuardianLogSource(entry.substring(0, separator), Path.of(entry.substring(separator + 1))));
        }
        return List.copyOf(result);
    }
}
