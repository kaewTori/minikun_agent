package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionResponse;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.notification.NotificationRequest;

class BackgroundChatServiceTest {
    @Test
    void restartBlocksOperationalWritesEvenWithANewModelCallId() throws Exception {
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        com.minikun.tools.Tool tool = new com.minikun.tools.Tool() {
            public com.minikun.tools.ToolDefinition definition() {
                return new com.minikun.tools.ToolDefinition("write", "write", java.util.Map.of());
            }
            public com.minikun.tools.ToolResult execute(com.minikun.tools.ToolCallContext context, java.util.Map<String, Object> args) {
                writes.incrementAndGet();
                return com.minikun.tools.ToolResult.success("saved");
            }
        };
        var executor = new com.minikun.tools.DefaultToolExecutor(new com.minikun.tools.DefaultToolRegistry(List.of(tool)));
        var chat = mock(ChatService.class);
        when(chat.chatCompletion(any(), any())).thenAnswer(call -> {
            executor.execute(new com.minikun.tools.ToolCallContext(new ConversationId("recovery"), "new-call"),
                    new com.minikun.tools.ToolCall("new-call", "write", java.util.Map.of()));
            return new ChatCompletionResponse("eval", "chat.completion", 1, "mini-kun",
                    List.of(new ChatCompletionResponse.Choice(0, new Message("assistant", "ตรวจผลเดิมก่อนครับ"), "stop")),
                    new ChatCompletionResponse.Usage(0, 0, 0));
        });
        var store = new BackgroundChatStore((org.springframework.jdbc.core.JdbcTemplate) null, new ObjectMapper());
        UUID id = UUID.randomUUID();
        store.create(id, new ChatCompletionRequest("mini-kun", List.of(new Message("user", "ทำต่อ")),
                "recovery", false, null, null, null), "recovery");
        var notified = new CountDownLatch(1);
        try (var service = new BackgroundChatService(chat, ignored -> notified.countDown(), store)) {
            service.resumePending();
            assertTrue(notified.await(2, TimeUnit.SECONDS));
            assertEquals("review_required", service.find(id).orElseThrow().status());
            assertEquals("REVIEW_REQUIRED", store.find(id).orElseThrow().status());
            assertEquals(0, writes.get());
            org.junit.jupiter.api.Assertions.assertFalse(service.resume(id));
        }
    }

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

    @Test
    void failsAStuckJobInsteadOfLeavingTheBrowserPollingForever() throws Exception {
        ChatService chat = mock(ChatService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(chat.chatCompletion(any(), any())).thenAnswer(ignored -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        });

        try (BackgroundChatService service = new BackgroundChatService(
                chat, ignored -> { }, (BackgroundChatStore) null, Duration.ofMillis(100))) {
            UUID id = service.submit(new ChatCompletionRequest("mini-kun",
                    List.of(new Message("user", "ค้นข้อมูล")), "conversation-timeout",
                    false, null, null, null), new ConversationId("conversation-timeout"));

            assertTrue(started.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ("running".equals(service.find(id).orElseThrow().status())
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            var result = service.find(id).orElseThrow();
            assertEquals("failed", result.status());
            assertTrue(result.error().contains("เวลานานเกินไป"));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void repeatedIdempotencyKeyReturnsTheOriginalJob() throws Exception {
        ChatService chat = mock(ChatService.class);
        ChatCompletionResponse response = new ChatCompletionResponse(
                "chatcmpl-idempotent", "chat.completion", 1, "mini-kun",
                List.of(new ChatCompletionResponse.Choice(0, new Message("assistant", "ครั้งเดียว"), "stop")),
                new ChatCompletionResponse.Usage(1, 1, 2));
        when(chat.chatCompletionInBackground(any(), any(), any()))
                .thenReturn(new ChatService.ChatCompletionOutcome(response, null));
        BackgroundChatStore store = new BackgroundChatStore((org.springframework.jdbc.core.JdbcTemplate) null,
                new ObjectMapper());

        try (BackgroundChatService service = new BackgroundChatService(chat, ignored -> { }, store)) {
            ChatCompletionRequest request = new ChatCompletionRequest("mini-kun",
                    List.of(new Message("user", "ทำครั้งเดียว")), "idempotent", false,
                    null, null, null, null, "owner");
            UUID first = service.submit(request, new ConversationId("idempotent"), "message-1");
            UUID second = service.submit(request, new ConversationId("idempotent"), "message-1");

            assertEquals(first, second);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!"completed".equals(service.find(first).orElseThrow().status())
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            verify(chat, times(1)).chatCompletionInBackground(any(), any(), any());
        }
    }

    @Test
    void textCompletesBeforeTheIllustrationAndTheAttachmentIsAddedLater() throws Exception {
        ChatService chat = mock(ChatService.class);
        ChatCompletionResponse response = new ChatCompletionResponse(
                "chatcmpl-image", "chat.completion", 1, "mini-kun",
                List.of(new ChatCompletionResponse.Choice(0, new Message("assistant", "ข้อความก่อนภาพ"), "stop")),
                new ChatCompletionResponse.Usage(1, 1, 2));
        ChatService.IllustrationTask task = new ChatService.IllustrationTask(
                "owner", "image-job", "ช่วยวาดภาพ", "ข้อความก่อนภาพ", true,
                "chatcmpl-image", java.time.Instant.now().plusSeconds(10));
        CountDownLatch imageStarted = new CountDownLatch(1);
        CountDownLatch releaseImage = new CountDownLatch(1);
        ChatAttachment attachment = new ChatAttachment("image", "/v1/images/generated/test.png", "ภาพประกอบ");
        when(chat.chatCompletionInBackground(any(), any(), any()))
                .thenReturn(new ChatService.ChatCompletionOutcome(response, task));
        when(chat.completeIllustration(any())).thenAnswer(ignored -> {
            imageStarted.countDown();
            releaseImage.await(2, TimeUnit.SECONDS);
            return new com.minikun.visual.StoryIllustrationService.IllustrationResult(List.of(attachment), "");
        });

        try (BackgroundChatService service = new BackgroundChatService(
                chat, ignored -> { }, (BackgroundChatStore) null, Duration.ofSeconds(10), 2, 1)) {
            UUID id = service.submit(new ChatCompletionRequest("mini-kun",
                    List.of(new Message("user", "ช่วยวาดภาพ")), "image-job", false,
                    null, null, null), new ConversationId("image-job"));
            assertTrue(imageStarted.await(2, TimeUnit.SECONDS));
            var textReady = service.find(id).orElseThrow();
            assertEquals("completed", textReady.status());
            assertEquals("running", textReady.imageStatus());
            assertEquals("ข้อความก่อนภาพ", textReady.response().choices().getFirst().message().content());

            releaseImage.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!"completed".equals(service.find(id).orElseThrow().imageStatus())
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            var finished = service.find(id).orElseThrow();
            assertEquals("completed", finished.imageStatus());
            assertEquals(1, finished.response().attachments().size());
        }
    }

    @Test
    void capsWaitingBackgroundJobsWithoutBlockingTheActiveWorker() throws Exception {
        ChatService chat = mock(ChatService.class);
        ChatCompletionResponse response = new ChatCompletionResponse(
                "chatcmpl-queue", "chat.completion", 1, "mini-kun",
                List.of(new ChatCompletionResponse.Choice(0, new Message("assistant", "พร้อม"), "stop")),
                new ChatCompletionResponse.Usage(1, 1, 2));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(chat.chatCompletionInBackground(any(), any(), any())).thenAnswer(ignored -> {
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown();
                releaseFirst.await(2, TimeUnit.SECONDS);
            }
            return new ChatService.ChatCompletionOutcome(response, null);
        });

        try (BackgroundChatService service = new BackgroundChatService(
                chat, ignored -> { }, (BackgroundChatStore) null, Duration.ofSeconds(5), 1, 1, 2, 1,
                (ChatPerformanceMetrics) null)) {
            UUID first = service.submit(new ChatCompletionRequest("mini-kun",
                    List.of(new Message("user", "หนึ่ง")), "queue", false, null, null, null),
                    new ConversationId("queue"));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            UUID second = service.submit(new ChatCompletionRequest("mini-kun",
                    List.of(new Message("user", "สอง")), "queue", false, null, null, null),
                    new ConversationId("queue"));
            UUID third = service.submit(new ChatCompletionRequest("mini-kun",
                    List.of(new Message("user", "สาม")), "queue", false, null, null, null),
                    new ConversationId("queue"));

            assertEquals("queued", service.find(second).orElseThrow().status());
            assertEquals("failed", service.find(third).orElseThrow().status());
            assertTrue(service.find(third).orElseThrow().error().contains("คิวงานเบื้องหลังเต็ม"));

            releaseFirst.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!"completed".equals(service.find(second).orElseThrow().status())
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals("completed", service.find(second).orElseThrow().status());
        }
    }
}
