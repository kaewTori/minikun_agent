package com.minikun.personality.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.minikun.personality.learning.AdaptationSnapshot;
import com.minikun.personality.learning.AdaptationSignalStore;
import com.minikun.personality.learning.AdaptivePreferenceLearningService;
import com.minikun.personality.learning.InMemoryAdaptationSignalStore;
import com.minikun.personality.learning.ResponsePreferenceDetector;
import com.minikun.personality.preference.InMemoryPreferenceStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChatFeedbackServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC);
    private final AdaptivePreferenceLearningService learning = new AdaptivePreferenceLearningService(
            new InMemoryAdaptationSignalStore(), new InMemoryPreferenceStore(), new ResponsePreferenceDetector(),
            clock, true, 3, .6, Duration.ofDays(365));
    private final ChatFeedbackService service = new ChatFeedbackService(
            new InMemoryChatFeedbackStore(), learning, clock);

    @Test
    void categorizesNaturalLanguageReasonAndLearnsBoundedPreference() {
        ChatFeedback feedback = service.submit("owner", "conversation", "message", "down", null,
                "คำตอบยาวและเยิ่นเกินไป");

        assertEquals(ChatFeedbackCategory.TOO_LONG, feedback.category());
        AdaptationSnapshot snapshot = learning.snapshot("owner");
        assertEquals("concise", snapshot.activePreferences().getFirst().value());
    }

    @Test
    void recordsContextFailureWithoutInferringStylePreference() {
        ChatFeedback feedback = service.submit("owner", "conversation", "message", "down", null,
                "จำบริบทผิด ไม่ใช่เรื่องนี้");

        assertEquals(ChatFeedbackCategory.CONTEXT_WRONG, feedback.category());
        assertEquals(0, learning.snapshot("owner").activePreferences().size());
    }

    @Test
    void classifiesQualityFailuresAndNotifiesObserver() {
        AtomicReference<ChatFeedback> observed = new AtomicReference<>();
        ChatFeedbackService observedService = new ChatFeedbackService(
                new InMemoryChatFeedbackStore(), learning, clock, java.util.List.of(observed::set));

        ChatFeedback feedback = observedService.submit("owner", "conversation", "response-id", "down", null,
                "ค้นผิดและใช้แหล่งข้อมูลผิด");

        assertEquals(ChatFeedbackCategory.SEARCH_WRONG, feedback.category());
        assertEquals(feedback, observed.get());
        assertEquals(0, learning.snapshot("owner").activePreferences().size());
    }

    @Test
    void labelsMissingImageWithoutLearningAnUnrelatedPreference() {
        ChatFeedback feedback = service.submit("owner", "conversation", "response-id", "down", null,
                "มินิคุงยังไม่เห็นรูปภาพ");

        assertEquals(ChatFeedbackCategory.VISION_WRONG, feedback.category());
        assertEquals(0, learning.snapshot("owner").activePreferences().size());
    }
}
