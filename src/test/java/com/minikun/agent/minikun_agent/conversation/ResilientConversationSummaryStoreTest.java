package com.minikun.agent.minikun_agent.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

class ResilientConversationSummaryStoreTest {
    @Test
    void databaseFreeFallbackRemainsOwnerAndConversationScoped() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> jdbc = mock(ObjectProvider.class);
        when(jdbc.getIfAvailable()).thenReturn(null);
        ResilientConversationSummaryStore store = new ResilientConversationSummaryStore(jdbc);
        ConversationSummary summary = new ConversationSummary(
                "owner-a", new ConversationId("conversation-a"), "summary",
                List.of("fingerprint"), 1, Instant.parse("2026-08-22T12:00:00Z"));

        store.save(summary);

        assertEquals(summary, store.find("owner-a", new ConversationId("conversation-a")).orElseThrow());
        assertEquals(true, store.find("owner-b", new ConversationId("conversation-a")).isEmpty());
        assertEquals(true, store.find("owner-a", new ConversationId("conversation-b")).isEmpty());
        assertEquals(true, store.delete("owner-a", new ConversationId("conversation-a")));
        assertEquals(true, store.find("owner-a", new ConversationId("conversation-a")).isEmpty());
    }
}
