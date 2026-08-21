package com.minikun.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class CooperativeChatModelServiceTest {
    @Test
    void reviewsTechnicalDraftProducedByAnExternalFrontLineRuntime() {
        ChatModelProvider frontLine = provider(ChatModelId.EXISTING, "unused");
        ChatModelProvider verifier = mock(ChatModelProvider.class);
        when(verifier.id()).thenReturn(ChatModelId.TINYGRAD);
        when(verifier.chat(any(Prompt.class))).thenReturn(
                response("คำตอบที่ผ่านการตรวจ สำหรับ Spring Boot 130 apps บน RAM 32 GB"));
        CooperativeChatModelService service = new CooperativeChatModelService(
                new DefaultChatModelProviderRegistry(List.of(frontLine, verifier)),
                new CooperativeReviewStore(),
                new CooperationRouter(),
                qualityGate(),
                true,
                "blocking",
                Duration.ofSeconds(2),
                12_000);
        Prompt prompt = new Prompt(
                "วิเคราะห์ config JVM สำหรับ Spring Boot 130 apps บน RAM 32 GB");

        ChatResponse result = service.reviewDraft(
                frontLine, prompt, response("ollama draft"), "technical-review");

        assertEquals("คำตอบที่ผ่านการตรวจ สำหรับ Spring Boot 130 apps บน RAM 32 GB",
                result.getResult().getOutput().getText());
        verify(verifier).chat(any(Prompt.class));
    }

    @Test
    void blockingFallsBackToDraftWhenReviewerChangesImmutableFacts() {
        ChatModelProvider frontLine = provider(ChatModelId.EXISTING, "unused");
        ChatModelProvider verifier = mock(ChatModelProvider.class);
        when(verifier.id()).thenReturn(ChatModelId.TINYGRAD);
        when(verifier.chat(any(Prompt.class))).thenReturn(response(
                "ใช้ Java 21 และ Spring Boot 4.1 แบบ bare metal บน RAM 32 GB สำหรับ 130 apps"));
        CooperativeChatModelService service = new CooperativeChatModelService(
                new DefaultChatModelProviderRegistry(List.of(frontLine, verifier)),
                new CooperativeReviewStore(),
                new CooperationRouter(),
                qualityGate(),
                true,
                "blocking",
                Duration.ofSeconds(2),
                12_000);
        Prompt prompt = new Prompt(
                "ใช้ JDK25 Spring Boot 4.1 แบบ bare metal บน RAM 32 GB สำหรับ 130 apps");

        String safeDraft = "ใช้ JDK25 Spring Boot 4.1 แบบ bare metal บน RAM 32 GB สำหรับ 130 apps "
                + "โดยตั้ง total process budget ไม่เกิน 160 MB ต่อ app";
        ChatResponse result = service.reviewDraft(
                frontLine, prompt, response(safeDraft), "quality-rejection");

        assertEquals(safeDraft, result.getResult().getOutput().getText());
    }

    @Test
    void hybridDoesNotReleaseUnsafeDraftBeforeBlockingReview() {
        ChatModelProvider frontLine = provider(ChatModelId.EXISTING, "unused");
        ChatModelProvider verifier = provider(ChatModelId.TINYGRAD,
                "ใช้ JDK25 Spring Boot 4.1 แบบ bare metal บน RAM 32 GB สำหรับ 130 apps "
                        + "โดยตั้ง total process budget 160 MB ต่อ app");
        CooperativeChatModelService service = new CooperativeChatModelService(
                new DefaultChatModelProviderRegistry(List.of(frontLine, verifier)),
                new CooperativeReviewStore(),
                new CooperationRouter(),
                qualityGate(),
                true,
                "hybrid",
                Duration.ofSeconds(2),
                12_000);
        Prompt prompt = new Prompt(
                "ใช้ JDK25 Spring Boot 4.1 แบบ bare metal บน RAM 32 GB สำหรับ 130 apps");

        ChatResponse result = service.reviewDraft(frontLine, prompt,
                response("ใช้ JDK25 Spring Boot 4.1 แบบ bare metal บน RAM 32 GB สำหรับ 130 apps -Xmx1024m"),
                "unsafe-hybrid-draft");

        assertEquals("ใช้ JDK25 Spring Boot 4.1 แบบ bare metal บน RAM 32 GB สำหรับ 130 apps "
                + "โดยตั้ง total process budget 160 MB ต่อ app", result.getResult().getOutput().getText());
    }

    @Test
    void returnsDeterministicSafeBoundWhenDraftAndReviewAreUnsafe() {
        ChatModelProvider frontLine = provider(ChatModelId.EXISTING, "unused");
        ChatModelProvider verifier = provider(ChatModelId.TINYGRAD,
                "ใช้ Java 21 Spring Boot 4.1 แบบ bare metal บน RAM 32 GB สำหรับ 130 apps -Xmx512m");
        CooperativeChatModelService service = new CooperativeChatModelService(
                new DefaultChatModelProviderRegistry(List.of(frontLine, verifier)),
                new CooperativeReviewStore(),
                new CooperationRouter(),
                qualityGate(),
                true,
                "blocking",
                Duration.ofSeconds(2),
                12_000);
        Prompt prompt = new Prompt(
                "ใช้ JDK25 Spring Boot 4.1 แบบ bare metal บน RAM 32 GB สำหรับ 130 apps");

        ChatResponse result = service.reviewDraft(frontLine, prompt,
                response("ใช้ JDK25 Spring Boot 4.1 แบบ bare metal บน RAM 32 GB สำหรับ 130 apps -Xmx1024m"),
                "both-unsafe");

        assertTrue(result.getResult().getOutput().getText().contains("214.25 MiB"));
        assertTrue(result.getResult().getOutput().getText().contains("ยังไม่ควรนำค่าจากคำตอบก่อนหน้าไปใช้จริง"));
    }

    @Test
    void hybridModeSubmitsTechnicalDraftForBackgroundReview() throws Exception {
        ChatModelProvider frontLine = provider(ChatModelId.EXISTING, "unused");
        CountDownLatch tinyGradCalled = new CountDownLatch(1);
        ChatModelProvider verifier = new ChatModelProvider() {
            @Override
            public ChatModelId id() {
                return ChatModelId.TINYGRAD;
            }

            @Override
            public ModelCapabilities capabilities() {
                return new ModelCapabilities(true, false, false);
            }

            @Override
            public ChatResponse chat(Prompt prompt) {
                tinyGradCalled.countDown();
                return response("คำตอบปรับปรุง");
            }

            @Override
            public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
                return reactor.core.publisher.Flux.just(response("คำตอบปรับปรุง"));
            }
        };
        CooperativeChatModelService service = new CooperativeChatModelService(
                new DefaultChatModelProviderRegistry(List.of(frontLine, verifier)),
                new CooperativeReviewStore(),
                new CooperationRouter(),
                qualityGate(),
                true,
                "hybrid",
                Duration.ofSeconds(2),
                12_000);
        ChatResponse draft = response("คำตอบหลัก");

        ChatResponse result = service.reviewDraft(
                frontLine,
                new Prompt("ช่วยวิเคราะห์ performance ของ Java code นี้"),
                draft,
                "hybrid-technical-review");

        assertEquals("คำตอบหลัก", result.getResult().getOutput().getText());
        assertTrue(tinyGradCalled.await(2, TimeUnit.SECONDS));
    }

    @Test
    void hybridModeMarksUnsafeRevisionAsRejected() {
        ChatModelProvider frontLine = provider(ChatModelId.EXISTING, "unused");
        ChatModelProvider verifier = provider(ChatModelId.TINYGRAD,
                "ใช้ Java 21 และ Spring Boot 4.1 แบบ bare metal บน RAM 32 GB สำหรับ 130 apps");
        CooperativeReviewStore store = new CooperativeReviewStore();
        CooperativeChatModelService service = new CooperativeChatModelService(
                new DefaultChatModelProviderRegistry(List.of(frontLine, verifier)),
                store,
                new CooperationRouter(),
                qualityGate(),
                true,
                "hybrid",
                Duration.ofSeconds(2),
                12_000);
        String conversationId = "hybrid-quality-rejection";

        service.reviewDraft(frontLine,
                new Prompt("ใช้ JDK25 Spring Boot 4.1 แบบ bare metal บน RAM 32 GB สำหรับ 130 apps"),
                response("ใช้ JDK25 Spring Boot 4.1 แบบ bare metal บน RAM 32 GB สำหรับ 130 apps "
                        + "โดยตั้ง total process budget 160 MB ต่อ app"), conversationId);

        CooperativeReviewStore.Review review = store.events(conversationId)
                .filter(value -> "REJECTED".equals(value.status()))
                .blockFirst(Duration.ofSeconds(2));
        assertEquals("REJECTED", review.status());
        assertTrue(review.error().contains("missing_or_changed_version:java=25"));
    }

    private ChatModelProvider provider(ChatModelId id, String answer) {
        return new ChatModelProvider() {
            @Override
            public ChatModelId id() {
                return id;
            }

            @Override
            public ModelCapabilities capabilities() {
                return new ModelCapabilities(true, id == ChatModelId.EXISTING, false);
            }

            @Override
            public ChatResponse chat(Prompt prompt) {
                return response(answer);
            }

            @Override
            public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
                return reactor.core.publisher.Flux.just(response(answer));
            }
        };
    }

    private CooperativeQualityGate qualityGate() {
        return new CooperativeQualityGate(true, 0.85, 0.08);
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
