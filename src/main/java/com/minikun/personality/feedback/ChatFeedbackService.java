package com.minikun.personality.feedback;

import com.minikun.personality.learning.AdaptationDimensions;
import com.minikun.personality.learning.AdaptivePreferenceLearningService;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Persists response feedback and translates closed categories into conservative preferences. */
public final class ChatFeedbackService {
    private final ChatFeedbackStore store;
    private final AdaptivePreferenceLearningService learning;
    private final Clock clock;
    private final List<ChatQualityFeedbackObserver> qualityObservers;

    public ChatFeedbackService(ChatFeedbackStore store, AdaptivePreferenceLearningService learning, Clock clock) {
        this(store, learning, clock, List.of());
    }

    public ChatFeedbackService(ChatFeedbackStore store, AdaptivePreferenceLearningService learning, Clock clock,
            List<ChatQualityFeedbackObserver> qualityObservers) {
        this.store = Objects.requireNonNull(store);
        this.learning = Objects.requireNonNull(learning);
        this.clock = Objects.requireNonNull(clock);
        this.qualityObservers = qualityObservers == null ? List.of() : List.copyOf(qualityObservers);
    }

    public ChatFeedback submit(String ownerId, String conversationId, String messageId,
            String rating, ChatFeedbackCategory requestedCategory, String reason) {
        ChatFeedbackCategory category = requestedCategory == null || requestedCategory == ChatFeedbackCategory.OTHER
                ? detect(reason) : requestedCategory;
        ChatFeedback feedback = store.save(new ChatFeedback(UUID.randomUUID(), ownerId, conversationId, messageId,
                rating, category, reason, clock.instant()));
        if ("DOWN".equals(feedback.rating())) apply(ownerId, category);
        qualityObservers.forEach(observer -> {
            try { observer.observe(feedback); } catch (RuntimeException ignored) { }
        });
        return feedback;
    }

    public List<ChatFeedback> list(String ownerId, int limit) {
        return store.list(ownerId, Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200)));
    }

    public boolean delete(String ownerId, String conversationId, String messageId) {
        return store.delete(ownerId, conversationId, messageId);
    }

    ChatFeedbackCategory detect(String reason) {
        String text = Objects.requireNonNullElse(reason, "").toLowerCase(Locale.ROOT);
        if (contains(text, "ยาว", "เยิ่น", "too long", "verbose")) return ChatFeedbackCategory.TOO_LONG;
        if (contains(text, "สั้นไป", "ไม่ละเอียด", "too short", "more detail")) return ChatFeedbackCategory.TOO_SHORT;
        if (contains(text, "ทางการ", "แข็ง", "formal", "stiff")) return ChatFeedbackCategory.TOO_FORMAL;
        if (contains(text, "เล่นเกิน", "กันเองเกิน", "too casual")) return ChatFeedbackCategory.TOO_CASUAL;
        if (contains(text, "ถามเยอะ", "คำถามเยอะ", "too many questions")) return ChatFeedbackCategory.TOO_MANY_QUESTIONS;
        if (contains(text, "รีบแนะนำ", "ยังไม่อยากได้คำแนะนำ", "advice too soon", "just listen")) {
            return ChatFeedbackCategory.ADVICE_TOO_SOON;
        }
        if (contains(text, "จำผิด", "บริบทผิด", "ไม่ใช่เรื่องนี้", "wrong context", "forgot")) {
            return ChatFeedbackCategory.CONTEXT_WRONG;
        }
        if (contains(text, "ค้นผิด", "ผลค้นหา", "แหล่งข้อมูลผิด", "wrong search", "bad source")) {
            return ChatFeedbackCategory.SEARCH_WRONG;
        }
        if (contains(text, "ไม่เห็นรูป", "อ่านรูปผิด", "วิเคราะห์ภาพผิด", "wrong image", "couldn't see the image")) {
            return ChatFeedbackCategory.VISION_WRONG;
        }
        if (contains(text, "ใช้เครื่องมือผิด", "เรียก tool ผิด", "tool ผิด", "wrong tool")) {
            return ChatFeedbackCategory.TOOL_WRONG;
        }
        if (contains(text, "ข้อมูลผิด", "ข้อเท็จจริงผิด", "ตอบผิด", "fact wrong", "incorrect fact")) {
            return ChatFeedbackCategory.FACT_WRONG;
        }
        if (contains(text, "ตามใจ", "เห็นด้วยหมด", "ไม่ทักท้วง", "too agreeable", "challenge")) {
            return ChatFeedbackCategory.TOO_AGREEABLE;
        }
        if (contains(text, "ไม่ลงมือ", "ควรทำให้", "ไม่ใช้ tool", "should have acted", "do it")) {
            return ChatFeedbackCategory.SHOULD_HAVE_ACTED;
        }
        return ChatFeedbackCategory.OTHER;
    }

    private void apply(String ownerId, ChatFeedbackCategory category) {
        try {
            switch (category) {
                case TOO_LONG -> learning.feedback(ownerId, AdaptationDimensions.RESPONSE_LENGTH, "concise", true);
                case TOO_SHORT -> learning.feedback(ownerId, AdaptationDimensions.RESPONSE_LENGTH, "detailed", true);
                case TOO_FORMAL -> learning.feedback(ownerId, AdaptationDimensions.TONE, "casual", true);
                case TOO_CASUAL -> learning.feedback(ownerId, AdaptationDimensions.TONE, "professional", true);
                case TOO_MANY_QUESTIONS -> learning.feedback(ownerId, AdaptationDimensions.QUESTION_FREQUENCY, "minimal", true);
                case ADVICE_TOO_SOON -> learning.feedback(ownerId, AdaptationDimensions.INITIATIVE, "low", true);
                case TOO_AGREEABLE -> learning.feedback(ownerId, AdaptationDimensions.CHALLENGE, "direct", true);
                case SHOULD_HAVE_ACTED -> learning.feedback(ownerId, AdaptationDimensions.INITIATIVE, "high", true);
                case CONTEXT_WRONG, FACT_WRONG, SEARCH_WRONG, VISION_WRONG, TOOL_WRONG, OTHER -> {
                    /* recorded for routing evaluation; no unsafe preference inference */
                }
            }
        } catch (RuntimeException ignored) {
            // Feedback persistence is authoritative; optional learning must not fail the request.
        }
    }

    private boolean contains(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }
}
