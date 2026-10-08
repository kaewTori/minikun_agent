package com.minikun.personality.learning;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.personality.preference.InMemoryPreferenceStore;
import java.time.*;
import org.junit.jupiter.api.Test;

class ThaiPreferenceSemanticsTest {
    @Test
    void temporaryAndQuotedInstructionsDoNotBecomeDefaults() {
        var detector = new ResponsePreferenceDetector();
        for (String text : new String[]{"วันนี้ขอสั้น ๆ", "แม่บอกว่าชอบตอบสั้น", "ถ้าต่อไปอยากให้ตอบสั้น", "เมื่อก่อนชอบให้ตอบสั้น"})
            assertTrue(detector.detectDurable(text).stream().noneMatch(AdaptationObservation::explicit), text);
        assertTrue(detector.detect("เมื่อก่อนชอบให้ตอบสั้น แต่ตอนนี้ขอละเอียด").stream()
            .anyMatch(o -> o.dimension().equals("response_length") && o.value().equals("detailed")));
    }
    @Test
    void explicitWithdrawalClearsTheOldDimensionWithoutASecondModelCall() {
        var detector = new ResponsePreferenceDetector();
        var observations = detector.detectDurable("เมื่อก่อนชอบให้ตอบสั้น แต่ตอนนี้ไม่แล้ว");
        assertEquals(1, observations.size());
        assertEquals("response_length", observations.getFirst().dimension());
        assertEquals("unset", observations.getFirst().value());
    }
    @Test
    void semanticPreferenceRequiresExactUserEvidenceAndOverridesAccumulatedOldSignals() {
        var detector = new ResponsePreferenceDetector(request -> """
            {"preferences":[{"dimension":"response_length","value":"detailed","evidence":"ตอนนี้ขอละเอียด"}]}
            """, new ObjectMapper());
        var signals = new InMemoryAdaptationSignalStore();
        var prefs = new InMemoryPreferenceStore();
        var clock = Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC);
        var service = new AdaptivePreferenceLearningService(signals, prefs, detector, clock, true, 3, .6, Duration.ofDays(365));
        for (int i=0; i<20; i++) signals.record("owner", "response_length", "concise", 1.5, true, .35, clock.instant());
        service.observe("owner", "เมื่อก่อนชอบให้ตอบสั้น แต่ตอนนี้ขอละเอียด");
        assertEquals("detailed", prefs.findByOwner("owner").getFirst().value());
        service.observe("owner", "เมื่อก่อนชอบให้ตอบละเอียด แต่ตอนนี้ไม่แล้ว");
        assertTrue(prefs.findByOwner("owner").isEmpty());
        assertTrue(detector.detectDurable("ชอบอ่านหนังสือ").stream().noneMatch(AdaptationObservation::explicit));
        assertTrue(prefs.findByOwner("other").isEmpty());
    }

    @Test
    void semanticExtractorCannotPromoteOnePlainRequestOrQuotedRequest() {
        var detector = new ResponsePreferenceDetector(request -> """
            {"preferences":[{"dimension":"response_length","value":"concise","evidence":"ตอบสั้น"}]}
            """, new ObjectMapper());
        assertTrue(detector.detectDurable("ช่วยตอบสั้นหน่อย").stream()
                .filter(o -> o.dimension().equals("response_length")).noneMatch(AdaptationObservation::explicit));
        assertTrue(detector.detectDurable("แม่บอกว่าให้ตอบสั้น").stream()
                .filter(o -> o.dimension().equals("response_length")).noneMatch(AdaptationObservation::explicit));

        var withdrawalModel = new ResponsePreferenceDetector(request -> """
            {"preferences":[{"dimension":"response_length","value":"unset","evidence":"ตอบสั้น"}]}
            """, new ObjectMapper());
        assertTrue(withdrawalModel.detectDurable("ช่วยตอบสั้นหน่อย").stream()
                .noneMatch(o -> "unset".equals(o.value())));
    }
}
