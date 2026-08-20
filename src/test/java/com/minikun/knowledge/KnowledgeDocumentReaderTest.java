package com.minikun.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeDocumentReaderTest {
    @TempDir Path root;

    @Test
    void readsOnlySupportedVisibleUtf8Documents() throws Exception {
        Files.writeString(root.resolve("notes.md"), "# Notes\nข้อมูลมินิคุง");
        Files.writeString(root.resolve(".env"), "SECRET=value");
        Files.writeString(root.resolve("private.pem"), "secret");
        Files.createDirectories(root.resolve(".hidden"));
        Files.writeString(root.resolve(".hidden/inside.md"), "hidden");
        KnowledgeDocumentReader reader = reader();

        List<KnowledgeDocument> documents = reader.read("knowledge", "", true);

        assertEquals(1, documents.size());
        assertEquals("notes.md", documents.getFirst().relativePath());
    }

    @Test
    void rejectsEscapesAndExplicitEnvironmentFiles() throws Exception {
        Files.writeString(root.resolve(".env"), "SECRET=value");
        KnowledgeDocumentReader reader = reader();
        assertThrows(IllegalArgumentException.class, () -> reader.read("knowledge", "../outside.md", false));
        assertThrows(IllegalArgumentException.class, () -> reader.read("knowledge", ".env", false));
    }

    private KnowledgeDocumentReader reader() {
        return new KnowledgeDocumentReader(List.of(new KnowledgeRoot("knowledge", root)), 4096, 10, 4);
    }
}
