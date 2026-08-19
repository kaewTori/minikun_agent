package com.minikun.computer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.guardian.GuardianCommandResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalComputerServiceTest {
    @TempDir Path directory;

    @Test
    void readsSearchesAndInspectsOnlyVisibleTextInsideConfiguredRoot() throws Exception {
        Files.createDirectories(directory.resolve("notes"));
        Files.writeString(directory.resolve("notes/project.md"), "Mini-kun roadmap\ntoken=super-secret");
        Files.writeString(directory.resolve(".hidden.txt"), "hidden");
        Files.writeString(directory.resolve("private.key"), "private");
        LocalComputerService service = service(new ArrayList<>());

        ComputerFileContent content = service.read("docs", "notes/project.md");
        List<ComputerSearchMatch> matches = service.search("docs", "", "roadmap", 10);
        ComputerFolderSnapshot folder = service.inspectFolder("docs", "");

        assertTrue(content.content().contains("token=[REDACTED]"));
        assertFalse(content.content().contains("super-secret"));
        assertEquals("notes/project.md", matches.getFirst().path());
        assertEquals(2, folder.fileCount());
        assertThrows(IllegalArgumentException.class, () -> service.read("docs", "../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> service.read("docs", ".hidden.txt"));
        assertThrows(IllegalArgumentException.class, () -> service.read("docs", "private.key"));
        assertThrows(IllegalArgumentException.class, () -> service.read("unknown", "notes/project.md"));
    }

    @Test
    void writesMovesAndTrashesOnlyFromAnUnchangedPreview() throws Exception {
        List<ComputerAudit> audits = new ArrayList<>();
        LocalComputerService service = service(audits);
        Map<String, Object> write = Map.of("action", "write", "root", "docs",
                "path", "draft.txt", "content", "first draft");
        ComputerOperationPreview preview = service.preview(write);

        assertFalse(Files.exists(directory.resolve("draft.txt")));
        service.execute(preview.executionArguments(), "owner", "conversation");
        assertEquals("first draft", Files.readString(directory.resolve("draft.txt")));

        ComputerOperationPreview stale = service.preview(Map.of("action", "write", "root", "docs",
                "path", "draft.txt", "content", "second draft"));
        Files.writeString(directory.resolve("draft.txt"), "changed elsewhere");
        assertThrows(IllegalArgumentException.class,
                () -> service.execute(stale.executionArguments(), "owner", "conversation"));

        ComputerOperationPreview move = service.preview(Map.of("action", "move", "root", "docs",
                "path", "draft.txt", "target", "renamed.txt"));
        service.execute(move.executionArguments(), "owner", "conversation");
        assertTrue(Files.exists(directory.resolve("renamed.txt")));

        ComputerOperationPreview trash = service.preview(Map.of("action", "trash", "root", "docs",
                "path", "renamed.txt"));
        Map<String, Object> result = service.execute(trash.executionArguments(), "owner", "conversation");
        assertFalse(Files.exists(directory.resolve("renamed.txt")));
        assertTrue((Boolean) result.get("recoverable"));
        assertTrue(Files.isDirectory(directory.resolve(".minikun-trash")));
        assertEquals(List.of("COMPLETED", "FAILED", "COMPLETED", "COMPLETED"),
                audits.stream().map(ComputerAudit::status).toList());
    }

    @Test
    void rejectsSymlinkEscapeAndUnapprovedExternalActions() throws Exception {
        Path outside = Files.createTempDirectory("computer-outside-");
        Files.writeString(outside.resolve("secret.txt"), "outside");
        try {
            Files.createSymbolicLink(directory.resolve("escape"), outside);
            LocalComputerService service = service(new ArrayList<>());
            assertThrows(IllegalArgumentException.class, () -> service.read("docs", "escape/secret.txt"));
            assertThrows(IllegalArgumentException.class,
                    () -> service.preview(Map.of("action", "open_app", "application", "Terminal")));
            assertThrows(IllegalArgumentException.class,
                    () -> service.preview(Map.of("action", "open_url", "url", "file:///etc/passwd")));
        } finally {
            Files.deleteIfExists(outside.resolve("secret.txt"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void redactsClipboardAndRunsOnlyTheConfiguredWorkflowArgv() {
        List<ComputerAudit> audits = new ArrayList<>();
        AtomicReference<List<String>> executed = new AtomicReference<>();
        ComputerAuditStore audit = new ComputerAuditStore() {
            @Override public void save(ComputerAudit value) { audits.add(value); }
            @Override public List<ComputerAudit> list(String ownerId, int limit) { return audits; }
        };
        ComputerCommandGateway commands = new ComputerCommandGateway((command, timeout) -> {
            if (command.equals(List.of("/usr/bin/pbpaste"))) {
                return new GuardianCommandResult(0, false, "token=clipboard-secret");
            }
            executed.set(command);
            return new GuardianCommandResult(0, false, "ignored output");
        }, Duration.ofSeconds(1), List.of(new ComputerWorkflowDefinition(
                "refresh-index", "Refresh local index", List.of("/fixed/indexer", "--refresh"))), Set.of());
        LocalComputerService service = new LocalComputerService(
                List.of(new ComputerRoot("docs", directory)), audit, commands,
                Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC),
                65_536, 65_536, 4, 100);

        assertEquals("token=[REDACTED]", service.clipboard("owner", "conversation"));
        assertFalse(service.workflows().toString().contains("/fixed/indexer"));
        ComputerOperationPreview preview = service.preview(
                Map.of("action", "workflow", "workflow_id", "refresh-index"));
        Map<String, Object> result = service.execute(preview.executionArguments(), "owner", "conversation");

        assertEquals(List.of("/fixed/indexer", "--refresh"), executed.get());
        assertFalse(result.toString().contains("/fixed/indexer"));
        assertEquals(List.of("READ", "COMPLETED"), audits.stream().map(ComputerAudit::status).toList());
    }

    private LocalComputerService service(List<ComputerAudit> audits) {
        ComputerAuditStore audit = new ComputerAuditStore() {
            @Override public void save(ComputerAudit value) { audits.add(value); }
            @Override public List<ComputerAudit> list(String ownerId, int limit) {
                return audits.stream().filter(value -> ownerId.equals(value.ownerId())).limit(limit).toList();
            }
        };
        ComputerCommandGateway commands = new ComputerCommandGateway(
                (command, timeout) -> new GuardianCommandResult(0, false, "clipboard text"),
                Duration.ofSeconds(1), List.of(), Set.of("Finder"));
        return new LocalComputerService(List.of(new ComputerRoot("docs", directory)), audit, commands,
                Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC),
                65_536, 65_536, 4, 100);
    }
}
