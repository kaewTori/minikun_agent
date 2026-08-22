package com.minikun.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import com.minikun.knowledge.PersonalKnowledgeService;

/** Search and maintain owner-scoped local personal knowledge. */
public final class PersonalKnowledgeTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "knowledge.personal",
            "Use the user's indexed personal documents. Actions: status, sources, search, index, reindex. "
                    + "Use search for questions about the user's notes, projects, decisions, references, or files. "
                    + "Index only when the user explicitly asks to add/update a relative file or directory inside "
                    + "a configured knowledge root. Never pass an absolute path. Source deletion is not available "
                    + "through this tool.",
            parameters());

    private static Map<String, ToolParameter> parameters() {
        Map<String, ToolParameter> result = new LinkedHashMap<>();
        result.put("action", new ToolParameter("action", ToolParameterType.STRING, true,
                "One of status, sources, search, index, reindex."));
        result.put("query", new ToolParameter("query", ToolParameterType.STRING, false,
                "Search query with 1 to 500 characters."));
        result.put("root", new ToolParameter("root", ToolParameterType.STRING, false,
                "Configured logical knowledge root name."));
        result.put("path", new ToolParameter("path", ToolParameterType.STRING, false,
                "Relative file or directory path inside the root."));
        result.put("recursive", new ToolParameter("recursive", ToolParameterType.BOOLEAN, false,
                "Index nested directories when true."));
        result.put("force", new ToolParameter("force", ToolParameterType.BOOLEAN, false,
                "Rebuild unchanged sources when true."));
        result.put("limit", new ToolParameter("limit", ToolParameterType.INTEGER, false,
                "Search/source result limit. Search supports 1-20; sources supports 1-100."));
        return Map.copyOf(result);
    }

    private final PersonalKnowledgeService knowledge;

    public PersonalKnowledgeTool(PersonalKnowledgeService knowledge) {
        this.knowledge = java.util.Objects.requireNonNull(knowledge, "personal knowledge service must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public boolean requiresExplicitConfirmation(Map<String, Object> arguments) {
        String action = text(arguments, "action").toLowerCase(java.util.Locale.ROOT);
        return "index".equals(action) || "reindex".equals(action);
    }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        try {
            String action = text(arguments, "action").toLowerCase(java.util.Locale.ROOT);
            String owner = context.ownerId();
            return switch (action) {
                case "status" -> ToolResult.success(knowledge.status(owner));
                case "sources" -> ToolResult.success(Map.of("sources", knowledge.sources(owner,
                        Math.min(100, limit(arguments, 100)))));
                case "search" -> ToolResult.success(Map.of("results", knowledge.search(owner,
                        required(arguments, "query"), Math.min(20, limit(arguments, 5)))));
                case "index" -> ToolResult.success(knowledge.index(owner,
                        required(arguments, "root"), text(arguments, "path"),
                        bool(arguments, "recursive"), bool(arguments, "force")));
                case "reindex" -> ToolResult.success(knowledge.reindex(owner, bool(arguments, "force")));
                default -> ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                        "unsupported personal knowledge action");
            };
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "personal knowledge is unavailable");
        }
    }

    private String required(Map<String, Object> arguments, String key) {
        String value = text(arguments, key);
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private boolean bool(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value instanceof Boolean bool ? bool : value != null && Boolean.parseBoolean(value.toString());
    }

    private int limit(Map<String, Object> arguments, int fallback) {
        Object value = arguments == null ? null : arguments.get("limit");
        int result = value == null ? fallback : value instanceof Number number
                ? number.intValue() : Integer.parseInt(value.toString());
        if (result < 1 || result > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return result;
    }
}
