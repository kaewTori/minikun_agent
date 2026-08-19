package com.minikun.computer;

public record ComputerFileContent(
        String root, String path, long sizeBytes, String sha256, boolean truncated, String content) {}
