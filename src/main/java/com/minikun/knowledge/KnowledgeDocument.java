package com.minikun.knowledge;

import java.nio.file.Path;
import java.time.Instant;

record KnowledgeDocument(String root, String relativePath, String name, Path path,
        String content, String hash, Instant modifiedAt) {
}
