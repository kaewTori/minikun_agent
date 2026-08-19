package com.minikun.tools;

import com.minikun.computer.ComputerOperationPreview;
import com.minikun.computer.LocalComputerService;
import com.minikun.planner.PlannerConfirmationService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** A single bounded tool for local files, clipboard, approved applications, and fixed workflows. */
@Component
@ConditionalOnProperty(name = "minikun.computer.enabled", havingValue = "true", matchIfMissing = true)
public final class LocalComputerTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "computer.local",
            "Work with the user's local computer only inside application-configured named roots. "
                    + "Read-only actions are roots, list, search, read, inspect_folder, clipboard, applications, "
                    + "workflows, and audit. Mutating or external actions are write, move, trash, open_path, "
                    + "open_url, open_app, and workflow; they always return a preview and require explicit "
                    + "confirmation in the next user turn. Never pass shell commands or absolute paths. "
                    + "Use clipboard only when the user explicitly asks to read the clipboard.",
            parameters());

    private static Map<String, ToolParameter> parameters() {
        Map<String, ToolParameter> result = new LinkedHashMap<>();
        result.put("action", new ToolParameter("action", ToolParameterType.STRING, true,
                "One supported action named in the tool description."));
        result.put("root", new ToolParameter("root", ToolParameterType.STRING, false,
                "Logical root name returned by roots; never an absolute path."));
        result.put("path", new ToolParameter("path", ToolParameterType.STRING, false,
                "Relative file or directory path inside root. Use an empty string for the root directory."));
        result.put("target", new ToolParameter("target", ToolParameterType.STRING, false,
                "Relative destination path for move."));
        result.put("query", new ToolParameter("query", ToolParameterType.STRING, false,
                "Filename or text query for search."));
        result.put("content", new ToolParameter("content", ToolParameterType.STRING, false,
                "Complete UTF-8 text content for write."));
        result.put("url", new ToolParameter("url", ToolParameterType.STRING, false,
                "Absolute HTTP or HTTPS URL for open_url."));
        result.put("application", new ToolParameter("application", ToolParameterType.STRING, false,
                "Exact allowlisted application name returned by applications."));
        result.put("workflow_id", new ToolParameter("workflow_id", ToolParameterType.STRING, false,
                "Exact workflow id returned by workflows."));
        result.put("limit", new ToolParameter("limit", ToolParameterType.INTEGER, false,
                "Result or audit limit from 1 to 100. Default 20."));
        result.put("confirmed", new ToolParameter("confirmed", ToolParameterType.BOOLEAN, false,
                "Reserved for the confirmation router; never set true in the proposal turn."));
        result.put("expected_sha256", new ToolParameter("expected_sha256", ToolParameterType.STRING, false,
                "Internal preview fingerprint reserved for the confirmation router; never provide this directly."));
        return Map.copyOf(result);
    }

    private final LocalComputerService computer;
    private final Optional<PlannerConfirmationService> confirmations;

    public LocalComputerTool(LocalComputerService computer, Optional<PlannerConfirmationService> confirmations) {
        this.computer = Objects.requireNonNull(computer, "local computer service must not be null");
        this.confirmations = Objects.requireNonNull(confirmations, "computer confirmations must not be null");
    }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        try {
            String action = text(arguments, "action").toLowerCase(java.util.Locale.ROOT);
            return switch (action) {
                case "roots" -> ToolResult.success(Map.of("roots", computer.roots()));
                case "list" -> readOnly(context, arguments, "list", Map.of("entries", computer.list(
                        text(arguments, "root"), text(arguments, "path"), limit(arguments))));
                case "search" -> readOnly(context, arguments, "search", Map.of("matches", computer.search(
                        text(arguments, "root"), text(arguments, "path"), text(arguments, "query"), limit(arguments))));
                case "read" -> readOnly(context, arguments, "read",
                        computer.read(text(arguments, "root"), text(arguments, "path")));
                case "inspect_folder" -> readOnly(context, arguments, "inspect_folder",
                        computer.inspectFolder(text(arguments, "root"), text(arguments, "path")));
                case "clipboard" -> ToolResult.success(Map.of("clipboard",
                        computer.clipboard(context.ownerId(), context.conversationId().value()), "redacted", true));
                case "applications" -> ToolResult.success(Map.of("applications", computer.applications()));
                case "workflows" -> ToolResult.success(Map.of("workflows", computer.workflows(),
                        "model_commands_allowed", false));
                case "audit" -> ToolResult.success(Map.of("audit", computer.audit(context.ownerId(), limit(arguments))));
                case "write", "move", "trash", "open_path", "open_url", "open_app", "workflow" ->
                        operation(context, arguments);
                default -> ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "unsupported computer action");
            };
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "local computer operation is unavailable");
        }
    }

    private ToolResult operation(ToolCallContext context, Map<String, Object> arguments) {
        if (!confirmed(arguments)) {
            if (confirmations.isEmpty()) return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "confirmation storage is unavailable; no operation was performed");
            ComputerOperationPreview preview = computer.preview(arguments);
            computer.recordRequested(context.ownerId(), context.conversationId().value(), preview);
            confirmations.get().save(context.conversationId(), context.ownerId(),
                    "computer.execute", preview.executionArguments());
            return ToolResult.success(Map.of("requires_confirmation", true, "operation", preview.operation(),
                    "preview", preview.summary(), "message",
                    "มินิคุงเตรียมรายการนี้ไว้แล้ว แต่ยังไม่ได้ดำเนินการครับ: " + preview.summary()
                            + "\nยืนยันให้ดำเนินการไหมครับ"));
        }
        if (confirmations.isEmpty()) return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "confirmation storage is unavailable; no operation was performed");
        var pending = confirmations.get().find(context.conversationId(), context.ownerId())
                .filter(value -> "computer.execute".equals(value.action()));
        if (pending.isEmpty()) return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                "no matching confirmed computer operation is pending");
        Map<String, Object> result = computer.execute(pending.get().arguments(),
                context.ownerId(), context.conversationId().value());
        confirmations.get().clear(context.conversationId());
        return ToolResult.success(result);
    }

    private int limit(Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("limit");
        int result = value == null ? 20 : value instanceof Number number
                ? number.intValue() : Integer.parseInt(value.toString());
        if (result < 1 || result > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return result;
    }
    private ToolResult readOnly(ToolCallContext context, Map<String, Object> arguments,
            String operation, Object result) {
        computer.recordRead(context.ownerId(), context.conversationId().value(), operation,
                text(arguments, "root"), text(arguments, "path"));
        return ToolResult.success(result);
    }
    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().trim();
    }
    private boolean confirmed(Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("confirmed");
        return value instanceof Boolean bool ? bool : value != null && Boolean.parseBoolean(value.toString());
    }
}
