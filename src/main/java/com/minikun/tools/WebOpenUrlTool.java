package com.minikun.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.minikun.browser.BrowserContentException;
import com.minikun.browser.BrowserContentService;
import com.minikun.browser.BrowserReadResult;
import com.minikun.pcs.KnowledgeCandidate;

/** Exposes the existing safe browser reader as a native function tool. */
@Component
public final class WebOpenUrlTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "web.open_url",
            "Open and read a public HTTP or HTTPS URL supplied by the user. Use this to inspect "
                    + "a specific article, documentation page, or link; do not use it to search the web.",
            Map.of("url", new ToolParameter("url", ToolParameterType.STRING, true,
                    "A single public http:// or https:// URL.")));

    private final BrowserContentService browserContentService;

    public WebOpenUrlTool(BrowserContentService browserContentService) {
        this.browserContentService = Objects.requireNonNull(
                browserContentService, "browser content service must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        String url = text(arguments, "url");
        if (url.isBlank()) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "URL is required");
        }
        try {
            List<String> urls = browserContentService.urlsIn(url);
            if (urls.size() != 1 || !urls.get(0).equals(url)) {
                return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                        "provide exactly one valid http or https URL");
            }
            BrowserReadResult read = browserContentService.readPartial(url);
            if (!read.candidates().isEmpty()) {
                KnowledgeCandidate candidate = read.candidates().get(0);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("url", url);
                response.put("content", candidate.content());
                return ToolResult.success(response);
            }
            String reason = read.failures().isEmpty()
                    ? "browser reader is disabled or returned no content"
                    : read.failures().get(0).reason();
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, reason);
        } catch (BrowserContentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "web page could not be opened");
        }
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().trim();
    }
}
