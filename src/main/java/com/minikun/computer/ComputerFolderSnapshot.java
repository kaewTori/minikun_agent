package com.minikun.computer;

import java.util.List;

public record ComputerFolderSnapshot(
        String root, String path, int fileCount, int directoryCount, long totalBytes,
        boolean truncated, List<ComputerEntry> recentFiles) {
    public ComputerFolderSnapshot {
        recentFiles = recentFiles == null ? List.of() : List.copyOf(recentFiles);
    }
}
