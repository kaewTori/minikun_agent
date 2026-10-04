package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.minikun.browser.BrowserContent;
import com.minikun.browser.BrowserContentService;
import com.minikun.pcs.DefaultKnowledgeConsolidationService;
import com.minikun.pcs.DefaultKnowledgeSelectionService;
import com.minikun.search.SearchSelectionSignalMapper;
import com.minikun.search.internal.DefaultSearchContextAwarenessService;
import com.minikun.search.internal.DefaultSearchQueryPlanningService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ChatKnowledgeResolverBrowserTest {
    @Test
    void passesFailedUrlStatusToPromptWithoutChallengePageEvidence() {
        ObjectProvider<com.minikun.memory.MemoryRecallService> memory = mock(ObjectProvider.class);
        var browser = new BrowserContentService(url -> new BrowserContent(url,
                "Verify you are human. Performance and security by Cloudflare.", "text/markdown", false), true, 5);
        var resolver = new ChatKnowledgeResolver(memory, null, null, null, null,
                new DefaultSearchQueryPlanningService(), new DefaultSearchContextAwarenessService(),
                new DefaultKnowledgeSelectionService(), new DefaultKnowledgeConsolidationService(),
                new SearchSelectionSignalMapper(), browser, null, null, null,
                new ChatKnowledgeResolver.Configuration(false, Duration.ofSeconds(5), true, false,
                        8, 5, 5, 0, Duration.ofSeconds(30)));
        var result = resolver.resolve(new ChatKnowledgeResolver.Request(
                "สรุป https://example.com", "browser-test", null, "default", false, ""));
        assertEquals(1, result.selection().selectedCandidates().size());
        String content = result.selection().selectedCandidates().get(0).content();
        assertTrue(content.contains("NOT website evidence"));
        assertTrue(content.contains("verification required"));
        assertFalse(content.contains("Performance and security"));
    }
}
