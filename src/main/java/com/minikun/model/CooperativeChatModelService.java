package com.minikun.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/** Lets Ollama answer first and uses TinyGrad as a precision pass when useful. */
@Service
@Slf4j
public final class CooperativeChatModelService {
    private static final int VERIFIER_FAILURE_THRESHOLD = 3;
    private static final long VERIFIER_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final ChatModelProviderRegistry registry;
    private final CooperativeReviewStore reviewStore;
    private final CooperationRouter router;
    private final CooperativeQualityGate qualityGate;
    private final boolean enabled;
    private final String mode;
    private final Duration verificationTimeout;
    private final int maxDraftCharacters;
    private final AtomicInteger verifierFailures = new AtomicInteger();
    private final AtomicLong verifierOpenUntil = new AtomicLong();

    @Autowired(required = false)
    private ModelPerformanceMetrics performanceMetrics;

    public CooperativeChatModelService(
            ChatModelProviderRegistry registry,
            CooperativeReviewStore reviewStore,
            CooperationRouter router,
            CooperativeQualityGate qualityGate,
            @Value("${minikun.model.cooperation.enabled:false}") boolean enabled,
            @Value("${minikun.model.cooperation.mode:blocking}") String mode,
            @Value("${minikun.model.cooperation.timeout:PT20S}") Duration verificationTimeout,
            @Value("${minikun.model.cooperation.max-draft-characters:12000}") int maxDraftCharacters) {
        this.registry = registry;
        this.reviewStore = reviewStore;
        this.router = router;
        this.qualityGate = qualityGate;
        this.enabled = enabled;
        this.mode = mode == null ? "blocking" : mode.trim().toLowerCase();
        this.verificationTimeout = verificationTimeout == null || verificationTimeout.isNegative()
                || verificationTimeout.isZero() ? Duration.ofSeconds(20) : verificationTimeout;
        this.maxDraftCharacters = Math.max(1000, maxDraftCharacters);
    }

    public ChatResponse chat(ChatModelProvider frontLine, Prompt prompt) {
        ChatResponse draft = frontLine.chat(prompt);
        return reviewDraft(frontLine, prompt, draft, "unknown");
    }

    public ChatResponse chat(ChatModelProvider frontLine, Prompt prompt, String conversationId) {
        ChatResponse draft = frontLine.chat(prompt);
        return reviewDraft(frontLine, prompt, draft, conversationId);
    }

    public ChatResponse chat(ChatModelProvider frontLine, Prompt prompt, String conversationId,
            CooperationRoutingDecision plannedDecision) {
        ChatResponse draft = frontLine.chat(prompt);
        return reviewDraft(frontLine, prompt, draft, conversationId, plannedDecision);
    }

    /**
     * Applies the cooperation policy to a draft produced by another front-line runtime.
     * This keeps native tool execution on the capable Ollama provider while still allowing
     * TinyGrad to review calculation and technical-analysis answers.
     */
    public ChatResponse reviewDraft(
            ChatModelProvider frontLine, Prompt prompt, ChatResponse draft, String conversationId) {
        return reviewDraft(frontLine, prompt, draft, conversationId, null);
    }

    public ChatResponse reviewDraft(ChatModelProvider frontLine, Prompt prompt, ChatResponse draft,
            String conversationId, CooperationRoutingDecision plannedDecision) {
        if (draft == null) {
            return null;
        }
        CooperationRoutingDecision decision = decision(frontLine, prompt, plannedDecision);
        if ("hybrid".equals(mode) && decision.needsExpert() && decision.risk() != CooperationRisk.HIGH) {
            CooperativeQualityGate.Decision draftQuality = qualityGate.evaluate(
                    prompt, text(draft), text(draft));
            if (draftQuality.accepted()) {
                submitReview(prompt, draft, conversationId, decision);
                return draft;
            }
            log.warn("process=model_cooperation event=draft_quality_rejected conversation_id={} reasons={} action=blocking_review",
                    conversationId, draftQuality.summary());
            return verifyOrFallback(prompt, draft, decision);
        }
        return decision.needsExpert() ? verifyOrFallback(prompt, draft, decision) : draft;
    }

    /** Buffer only precision-sensitive streams so an unverified draft is never emitted before its rewrite. */
    public Flux<ChatResponse> stream(ChatModelProvider frontLine, Prompt prompt) {
        return stream(frontLine, prompt, "unknown", null);
    }

    public Flux<ChatResponse> stream(ChatModelProvider frontLine, Prompt prompt, String conversationId) {
        return stream(frontLine, prompt, conversationId, null);
    }

    public Flux<ChatResponse> stream(ChatModelProvider frontLine, Prompt prompt, String conversationId,
            CooperationRoutingDecision plannedDecision) {
        CooperationRoutingDecision decision = decision(frontLine, prompt, plannedDecision);
        if (!decision.needsExpert()) {
            return frontLine.stream(prompt);
        }
        if ("hybrid".equals(mode) && decision.risk() != CooperationRisk.HIGH
                && !qualityGate.hasDeterministicConstraints(prompt)) {
            return streamAndReview(frontLine, prompt, conversationId, decision);
        }
        return frontLine.stream(prompt).collectList().flatMapMany(chunks -> {
            ChatResponse draft = merge(chunks);
            return draft == null ? Flux.empty() : Flux.just(verifyOrFallback(prompt, draft, decision));
        });
    }

    private Flux<ChatResponse> streamAndReview(
            ChatModelProvider frontLine, Prompt prompt, String conversationId,
            CooperationRoutingDecision decision) {
        StringBuilder draftText = new StringBuilder();
        return frontLine.stream(prompt)
                .doOnNext(response -> append(draftText, response))
                .doOnComplete(() -> submitReviewText(prompt, draftText.toString(), conversationId, decision));
    }

    private void submitReview(Prompt prompt, ChatResponse draft, String conversationId,
            CooperationRoutingDecision decision) {
        submitReviewText(prompt, draft.getResult().getOutput().getText(), conversationId, decision);
    }

    private void submitReviewText(Prompt prompt, String draftText, String conversationId,
            CooperationRoutingDecision decision) {
        if (draftText == null || draftText.isBlank()) {
            return;
        }
        reviewStore.pending(conversationId, draftText);
        log.info("process=model_cooperation event=review_submitted conversation_id={}", conversationId);
        CompletableFuture.runAsync(() -> {
            try {
                ChatResponse revised = verifyOrFallbackWithoutFallback(prompt, draftText, decision);
                String revisedText = revised.getResult().getOutput().getText();
                CooperativeQualityGate.Decision quality = qualityGate.evaluate(prompt, draftText, revisedText);
                if (quality.accepted()) {
                    reviewStore.completed(conversationId, draftText, revisedText);
                    log.info("process=model_cooperation event=review_completed conversation_id={}", conversationId);
                } else {
                    reviewStore.rejected(conversationId, draftText, revisedText, quality.summary());
                    log.warn("process=model_cooperation event=review_rejected conversation_id={} reasons={}",
                            conversationId, quality.summary());
                }
            } catch (RuntimeException exception) {
                reviewStore.failed(conversationId, draftText, exception.getMessage());
                log.warn("TinyGrad background verification failed conversation_id={}", conversationId, exception);
            }
        });
    }

    private ChatResponse verifyOrFallbackWithoutFallback(Prompt prompt, String draftText,
            CooperationRoutingDecision decision) {
        ChatModelProvider verifier = registry.get(ChatModelId.TINYGRAD);
        return callWithTimeout(verifier, verificationPrompt(prompt,
                new ChatResponse(List.of(new Generation(new AssistantMessage(draftText)))), decision));
    }

    private void append(StringBuilder target, ChatResponse response) {
        if (response != null && response.getResult() != null && response.getResult().getOutput() != null
                && response.getResult().getOutput().getText() != null) {
            target.append(response.getResult().getOutput().getText());
        }
    }

    private ChatResponse verifyOrFallback(Prompt prompt, ChatResponse draft,
            CooperationRoutingDecision decision) {
        try {
            ChatModelProvider verifier = registry.get(ChatModelId.TINYGRAD);
            ChatResponse revised = callWithTimeout(verifier, verificationPrompt(prompt, draft, decision));
            String draftText = draft.getResult().getOutput().getText();
            String revisedText = revised.getResult().getOutput().getText();
            CooperativeQualityGate.Decision quality = qualityGate.evaluate(prompt, draftText, revisedText);
            if (!quality.accepted()) {
                log.warn("process=model_cooperation event=review_rejected reasons={}", quality.summary());
                CooperativeQualityGate.Decision draftQuality = qualityGate.evaluate(
                        prompt, draftText, draftText);
                return draftQuality.accepted() ? draft : safeFallbackResponse(prompt, draft);
            }
            return revised;
        } catch (RuntimeException exception) {
            log.warn("TinyGrad verification failed; applying draft quality and safe fallback policy", exception);
            String draftText = text(draft);
            CooperativeQualityGate.Decision draftQuality = qualityGate.evaluate(
                    prompt, draftText, draftText);
            return draftQuality.accepted() ? draft : safeFallbackResponse(prompt, draft);
        }
    }

    private ChatResponse safeFallbackResponse(Prompt prompt, ChatResponse original) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(qualityGate.safeFallback(prompt)))),
                original.getMetadata());
    }

    private String text(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    private ChatResponse callWithTimeout(ChatModelProvider verifier, Prompt prompt) {
        if (System.nanoTime() < verifierOpenUntil.get()) {
            throw new IllegalStateException("TinyGrad verification circuit is open");
        }
        long started = System.nanoTime();
        String result = "success";
        try {
            ChatResponse response = CompletableFuture.supplyAsync(() -> verifier.chat(prompt))
                    .orTimeout(verificationTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
            verifierFailures.set(0);
            verifierOpenUntil.set(0);
            return response;
        } catch (CompletionException exception) {
            result = "error";
            recordVerifierFailure();
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException("TinyGrad verification timed out or failed", cause);
        } catch (RuntimeException exception) {
            result = "error";
            recordVerifierFailure();
            throw exception;
        } finally {
            if (performanceMetrics != null) {
                performanceMetrics.record("tinygrad", started, result);
            }
        }
    }

    private void recordVerifierFailure() {
        if (verifierFailures.incrementAndGet() >= VERIFIER_FAILURE_THRESHOLD) {
            verifierOpenUntil.set(System.nanoTime() + VERIFIER_COOLDOWN_NANOS);
            log.warn("process=model_cooperation event=circuit_open provider=tinygrad cooldown_seconds=30");
        }
    }

    private CooperationRoutingDecision decision(ChatModelProvider frontLine, Prompt prompt,
            CooperationRoutingDecision plannedDecision) {
        if (!enabled || frontLine.id() != ChatModelId.EXISTING) {
            return CooperationRoutingDecision.low();
        }
        String userText = prompt.getInstructions().stream()
                .filter(message -> "user".equalsIgnoreCase(message.getMessageType().getValue()))
                .map(Message::getText)
                .reduce((left, right) -> right)
                .orElse("");
        CooperationRoutingDecision result = plannedDecision == null ? router.decide(userText) : plannedDecision;
        if (result.needsExpert()) {
            log.info("process=model_cooperation event=routed risk={} reason={}",
                    result.risk(), result.reason());
        } else {
            log.debug("process=model_cooperation event=skipped risk={} reason={}",
                    result.risk(), result.reason());
        }
        return result;
    }

    private Prompt verificationPrompt(Prompt original, ChatResponse draft,
            CooperationRoutingDecision decision) {
        String draftText = draft.getResult().getOutput().getText();
        if (draftText == null) {
            draftText = "";
        }
        if (draftText.length() > maxDraftCharacters) {
            draftText = draftText.substring(0, maxDraftCharacters);
        }
        List<Message> messages = new ArrayList<>(original.getInstructions());
        messages.add(new UserMessage("""
                คุณคือ specialist reviewer ของผู้ช่วยหลัก จงตรวจคำตอบร่างด้านล่าง แล้วตอบผู้ใช้ด้วยคำตอบสุดท้ายเพียงครั้งเดียว
                ระดับความเสี่ยงและเหตุผลของการส่งงาน: %s
                ข้อเท็จจริงและข้อจำกัดที่ห้ามเปลี่ยน: %s
                รักษา personality, ภาษา, น้ำเสียง และรูปแบบการตอบจาก system prompt เดิม
                แก้ไขข้อเท็จจริง เหตุผล ตัวเลข และโค้ดที่ไม่ถูกต้อง หากข้อมูลไม่พอให้ระบุข้อจำกัดอย่างตรงไปตรงมา
                ต้องกล่าวถึงข้อเท็จจริงและข้อจำกัดข้างต้นด้วยค่าเดิม ห้ามแทนด้วย version หรือทรัพยากรอื่น
                สำหรับ capacity planning ให้แสดงสมการสำคัญ และรวม heap, metaspace, direct memory, code cache,
                thread/native memory รวมถึงพื้นที่ระบบปฏิบัติการก่อนเสนอค่าที่ใช้งานจริง
                ห้ามพูดถึงการตรวจสอบ โมเดล ร่างคำตอบ หรือขั้นตอนภายใน และอย่าเพิ่มข้อมูลที่ไม่มีหลักฐาน

                [คำตอบร่างจาก Ollama]
                %s
                """.formatted(routeHint(original, decision), qualityGate.constraints(original), draftText)));
        return new Prompt(messages, original.getOptions());
    }

    private String routeHint(Prompt prompt, CooperationRoutingDecision plannedDecision) {
        if (plannedDecision != null) {
            return plannedDecision.risk() + " / " + plannedDecision.reason();
        }
        String userText = prompt.getInstructions().stream()
                .filter(message -> "user".equalsIgnoreCase(message.getMessageType().getValue()))
                .map(Message::getText)
                .reduce((left, right) -> right)
                .orElse("");
        CooperationRoutingDecision decision = router.decide(userText);
        return decision.risk() + " / " + decision.reason();
    }

    private ChatResponse merge(List<ChatResponse> chunks) {
        StringBuilder text = new StringBuilder();
        ChatResponse last = null;
        for (ChatResponse chunk : chunks) {
            if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                continue;
            }
            last = chunk;
            String value = chunk.getResult().getOutput().getText();
            if (value != null) {
                text.append(value);
            }
        }
        return last == null
                ? null
                : new ChatResponse(List.of(new Generation(new AssistantMessage(text.toString()))), last.getMetadata());
    }
}
