package com.minikun.personality.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.personality.model.Preference;
import com.minikun.personality.preference.InMemoryPreferenceStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AdaptivePreferenceLearningServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void promotesRepeatedLanguageEvidenceWithoutStoringRawMessages() {
        var preferences = new InMemoryPreferenceStore();
        var service = service(preferences);

        service.observe("owner-a", "ช่วยดูเรื่องนี้ให้หน่อยครับ");
        service.observe("owner-a", "วันนี้มีอะไรต้องทำบ้างครับ");
        assertTrue(service.snapshot("owner-a").activePreferences().isEmpty());
        service.observe("owner-a", "สรุปงานที่ยังค้างอยู่ให้หน่อยครับ");

        AdaptationSnapshot snapshot = service.snapshot("owner-a");
        assertEquals("th", snapshot.activePreferences().getFirst().value());
        assertEquals(3, snapshot.evidence().getFirst().observations());
        assertFalse(snapshot.evidence().toString().contains("สรุปงาน"));
        assertTrue(service.snapshot("owner-b").activePreferences().isEmpty());
    }

    @Test
    void explicitInstructionAppliesImmediatelyAndNegativeFeedbackWithdrawsIt() {
        var service = service(new InMemoryPreferenceStore());

        service.observe("owner-a", "ต่อไปช่วยตอบสั้นๆ นะ");
        assertEquals("concise", value(service, AdaptationDimensions.RESPONSE_LENGTH));

        service.feedback("owner-a", AdaptationDimensions.RESPONSE_LENGTH, "concise", false);
        assertTrue(service.snapshot("owner-a").activePreferences().stream()
                .noneMatch(preference -> preference.key().endsWith(AdaptationDimensions.RESPONSE_LENGTH)));
    }

    @Test
    void resetDeletesOnlyLearnedPreferencesAndKeepsUserManagedPreferences() {
        var preferences = new InMemoryPreferenceStore();
        preferences.save(new Preference("owner-a", "food", "ไม่เผ็ด", 1, NOW));
        var service = service(preferences);
        service.feedback("owner-a", AdaptationDimensions.TONE, "casual", true);

        var result = service.reset("owner-a");

        assertEquals(1, result.deletedSignals());
        assertEquals(1, result.deletedPreferences());
        assertEquals("food", preferences.findByOwner("owner-a").getFirst().key());
    }

    private String value(AdaptivePreferenceLearningService service, String dimension) {
        return service.snapshot("owner-a").activePreferences().stream()
                .filter(preference -> preference.key().equals(AdaptationDimensions.preferenceKey(dimension)))
                .findFirst().orElseThrow().value();
    }

    private AdaptivePreferenceLearningService service(InMemoryPreferenceStore preferences) {
        return new AdaptivePreferenceLearningService(new InMemoryAdaptationSignalStore(), preferences,
                new ResponsePreferenceDetector(), Clock.fixed(NOW, ZoneOffset.UTC), true, 3, .6,
                java.time.Duration.ofDays(365));
    }
}
