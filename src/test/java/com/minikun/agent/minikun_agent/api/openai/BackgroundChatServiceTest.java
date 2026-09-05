package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionResponse;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.notification.NotificationRequest;

class BackgroundChatServiceTest {
    @Test
    void completesWithoutAWaitingBrowserAndNotifiesTheUser() throws Exception {
        ChatService chat = mock(ChatService.class);
        ChatCompletionResponse response = new ChatCompletionResponse(
                "chatcmpl-1", "chat.completion", 1, "mini-kun",
                List.of(new ChatCompletionResponse.Choice(0, new Message("assistant", "เสร็จแล้วครับ"), "stop")),
                new ChatCompletionResponse.Usage(1, 2, 3));
        when(chat.chatCompletion(any(), any())).thenReturn(response);
        CountDownLatch notified = new CountDownLatch(1);
        AtomicReference<NotificationRequest> notification = new AtomicReference<>();

        try (BackgroundChatService service = new BackgroundChatService(chat, request -> {
            notification.set(request);
            notified.countDown();
        })) {
            var id = service.submit(new ChatCompletionRequest(
                    "mini-kun", List.of(new Message("user", "ทำงานนี้ให้หน่อย")), "conversation-1",
                    false, null, null, null), new ConversationId("conversation-1"));

            assertTrue(notified.await(2, TimeUnit.SECONDS));
            var result = service.find(id).orElseThrow();
            assertEquals("completed", result.status());
            assertEquals(response, result.response());
            assertEquals("CHAT", notification.get().sourceType());
        }
    }

    @Test
    void resumesPersistedRunningJobAfterStartup() throws Exception {
        ChatService chat = mock(ChatService.class);
        ChatCompletionResponse response = new ChatCompletionResponse(
                "chatcmpl-2", "chat.completion", 1, "mini-kun",
                List.of(new ChatCompletionResponse.Choice(0, new Message("assistant", "กลับมาทำต่อแล้วครับ"), "stop")),
                new ChatCompletionResponse.Usage(1, 2, 3));
        CountDownLatch completed = new CountDownLatch(1);
        when(chat.chatCompletion(any(), any())).thenAnswer(ignored -> { completed.countDown(); return response; });
        BackgroundChatStore store = new BackgroundChatStore((org.springframework.jdbc.core.JdbcTemplate) null,
                new ObjectMapper());
        var request = new ChatCompletionRequest("mini-kun", List.of(new Message("user", "ทำต่อ")),
                "conversation-2", false, null, null, null);
        UUID id = UUID.randomUUID();
        store.create(id, request, "conversation-2");

        try (BackgroundChatService service = new BackgroundChatService(chat, ignored -> { }, store)) {
            service.resumePending();

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!"completed".equals(service.find(id).orElseThrow().status()) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals("completed", service.find(id).orElseThrow().status());
            assertEquals(response, service.find(id).orElseThrow().response());
        }
    }

    @Test
    void keepsRunningStateWhenServiceStopsSoTheNextStartupCanResume() throws Exception {
        ChatService chat = mock(ChatService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(chat.chatCompletion(any(), any())).thenAnswer(ignored -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("unreachable");
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("service stopped", exception);
            }
        });
        BackgroundChatStore store = new BackgroundChatStore((org.springframework.jdbc.core.JdbcTemplate) null,
                new ObjectMapper());
        BackgroundChatService service = new BackgroundChatService(chat, ignored -> { }, store);
        UUID id = service.submit(new ChatCompletionRequest("mini-kun", List.of(new Message("user", "ทำต่อหลังรีสตาร์ต")),
                "conversation-3", false, null, null, null), new ConversationId("conversation-3"));

        assertTrue(started.await(2, TimeUnit.SECONDS));
        service.close();

        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertEquals("RUNNING", store.find(id).orElseThrow().status());
    }
}
