package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.browser.BrowserContent;
import com.minikun.browser.BrowserContentService;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.SearchService;
import com.minikun.search.model.SearchRequest;

class FunctionCapabilityToolsTest {
    private static final ToolCallContext CONTEXT = new ToolCallContext(
            new ConversationId("conversation"), "call-1");

    @Test
    void currentTimeUsesRequestedTimezone() {
        CurrentTimeTool tool = new CurrentTimeTool(
                Clock.fixed(Instant.parse("2026-08-18T12:34:56Z"), ZoneOffset.UTC),
                "Asia/Bangkok");

        ToolResult result = tool.execute(CONTEXT, Map.of("timezone", "Asia/Bangkok"));
        Map<?, ?> value = (Map<?, ?>) result.value();

        assertTrue(result.success());
        assertEquals("2026-08-18", value.get("localDate"));
        assertEquals("19:34:56", value.get("localTime"));
        assertEquals("Asia/Bangkok", value.get("timezone"));
    }

    @Test
    void currentTimeRejectsUnknownTimezone() {
        CurrentTimeTool tool = new CurrentTimeTool(Clock.systemUTC(), "Asia/Bangkok");

        ToolResult result = tool.execute(CONTEXT, Map.of("timezone", "Not/A_Timezone"));

        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, result.errorCode());
    }

    @Test
    void webSearchDelegatesToExistingSearchService() {
        SearchRequest[] captured = new SearchRequest[1];
        SearchService searchService = request -> {
            captured[0] = request;
            return new KnowledgeContext("search content", List.of(
                    new KnowledgeCandidate("result-1", KnowledgeSource.SEARCH,
                            "A useful result", 0)));
        };
        WebSearchTool tool = new WebSearchTool(searchService, Duration.ofSeconds(10));

        ToolResult result = tool.execute(CONTEXT, Map.of("query", "Spring AI tools", "limit", 3));
        Map<?, ?> value = (Map<?, ?>) result.value();

        assertTrue(result.success());
        assertEquals("Spring AI tools", captured[0].query());
        assertEquals(3, captured[0].resultLimit());
        assertEquals(1, value.get("resultCount"));
    }

    @Test
    void webOpenUrlDelegatesToSafeBrowserReader() {
        BrowserContentService browser = new BrowserContentService(url -> new BrowserContent(
                url, "A rendered page with enough trusted content to pass quality checks.",
                "text/html", false), true, 1);
        WebOpenUrlTool tool = new WebOpenUrlTool(browser);

        ToolResult result = tool.execute(CONTEXT, Map.of("url", "https://example.com/docs"));
        Map<?, ?> value = (Map<?, ?>) result.value();

        assertTrue(result.success());
        assertEquals("https://example.com/docs", value.get("url"));
        assertTrue(value.get("content").toString().contains("Source URL:"));
    }
}
