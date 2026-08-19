package com.minikun.guardian;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

record GuardianBackupTarget(String name, Path path) {
    GuardianBackupTarget {
        if (name == null || name.isBlank() || path == null) {
            throw new IllegalArgumentException("guardian backup target requires name and path");
        }
        name = name.trim();
        path = path.toAbsolutePath().normalize();
    }

    static List<GuardianBackupTarget> parseList(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<GuardianBackupTarget> result = new ArrayList<>();
        for (String entry : value.split(",")) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalArgumentException("guardian backup targets must use name=path");
            }
            result.add(new GuardianBackupTarget(entry.substring(0, separator), Path.of(entry.substring(separator + 1))));
        }
        return List.copyOf(result);
    }
}
