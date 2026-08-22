package com.minikun.tools;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.SearchService;
import com.minikun.search.model.SearchOptions;
import com.minikun.search.model.SearchRequest;

/** Exposes the existing web-search pipeline as a native function tool. */
@Component
public final class WebSearchTool implements Tool {
    private static final int DEFAULT_RESULT_LIMIT = 5;
    private static final int MAX_RESULT_LIMIT = 10;
    private static final int MAX_SNIPPET_CHARACTERS = 2_000;
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "web.search",
            "Search the public web for current or specific information. Use this when the user asks "
                    + "to look up, search, verify, or find information online.",
            Map.of(
                    "query", new ToolParameter("query", ToolParameterType.STRING, true,
                            "The focused web search query."),
                    "limit", new ToolParameter("limit", ToolParameterType.INTEGER, false,
                            "Number of results to return, from 1 to 10. Default is 5."),
                    "language", new ToolParameter("language", ToolParameterType.STRING, false,
                            "Preferred result language, such as th or en."),
                    "time_range", new ToolParameter("time_range", ToolParameterType.STRING, false,
                            "Optional freshness range understood by the search provider."),
                    "safe_search", new ToolParameter("safe_search", ToolParameterType.BOOLEAN, false,
                            "Whether to enable safe search. Default is true.")));

    private final SearchService searchService;
    private final Duration timeout;

    public WebSearchTool(
            SearchService searchService,
            @Value("${minikun.search.timeout:10s}") Duration timeout) {
        this.searchService = Objects.requireNonNull(searchService, "search service must not be null");
        this.timeout = Objects.requireNonNull(timeout, "search timeout must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override public boolean requiresExplicitConfirmation(Map<String, Object> arguments) { return false; }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        String query = text(arguments, "query");
        if (query.isBlank()) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "search query is required");
        }
        int limit = integer(arguments, "limit", DEFAULT_RESULT_LIMIT);
        if (limit < 1 || limit > MAX_RESULT_LIMIT) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                    "search limit must be between 1 and " + MAX_RESULT_LIMIT);
        }
        try {
            KnowledgeContext knowledge = searchService.search(new SearchRequest(
                    UUID.randomUUID(), query, limit, Instant.now().plus(timeout),
                    new SearchOptions(text(arguments, "language"), "", text(arguments, "time_range"),
                            booleanValue(arguments, "safe_search", true)),
                    List.of()));
            return ToolResult.success(format(query, knowledge));
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "web search is temporarily unavailable");
        }
    }

    private Map<String, Object> format(String query, KnowledgeContext knowledge) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (knowledge != null) {
            for (KnowledgeCandidate candidate : knowledge.candidates()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("title", candidate.candidateId());
                result.put("content", snippet(candidate.content()));
                result.put("source", candidate.source().name());
                result.put("position", candidate.sourcePosition());
                results.add(result);
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", query);
        response.put("resultCount", results.size());
        response.put("results", results);
        if (results.isEmpty() && knowledge != null && !knowledge.content().isBlank()) {
            response.put("content", snippet(knowledge.content()));
        }
        return response;
    }

    private String snippet(String content) {
        if (content == null || content.length() <= MAX_SNIPPET_CHARACTERS) {
            return content == null ? "" : content;
        }
        return content.substring(0, MAX_SNIPPET_CHARACTERS) + "…";
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private int integer(Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? fallback : ((Number) value).intValue();
    }

    private boolean booleanValue(Map<String, Object> arguments, String key, boolean fallback) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? fallback : (Boolean) value;
    }
}
