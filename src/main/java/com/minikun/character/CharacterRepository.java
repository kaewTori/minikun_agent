package com.minikun.character;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class CharacterRepository {
    private final Path directory;

    CharacterRepository(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    Map<String, String> read() throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("Character directory does not exist: " + directory);
        }

        Map<String, String> files = new LinkedHashMap<>();
        try (var paths = Files.list(directory)) {
            paths.filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            files.put(path.getFileName().toString(), Files.readString(path, StandardCharsets.UTF_8));
                        } catch (IOException exception) {
                            throw new RepositoryReadException(exception);
                        }
                    });
        } catch (RepositoryReadException exception) {
            throw exception.cause;
        }
        return Map.copyOf(files);
    }

    private static final class RepositoryReadException extends RuntimeException {
        private final IOException cause;

        private RepositoryReadException(IOException cause) {
            this.cause = cause;
        }
    }
}
