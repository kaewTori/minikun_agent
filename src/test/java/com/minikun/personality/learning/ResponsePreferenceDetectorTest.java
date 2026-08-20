package com.minikun.personality.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ResponsePreferenceDetectorTest {
    private final ResponsePreferenceDetector detector = new ResponsePreferenceDetector();

    @Test
    void extractsOnlyClosedResponseStyleSignals() {
        Map<String, String> values = detector.detect(
                "ช่วยตอบสั้นๆ เป็นข้อ ใช้ภาษาไทยแบบเข้าใจง่ายและเป็นกันเอง")
                .stream().collect(Collectors.toMap(
                        AdaptationObservation::dimension, AdaptationObservation::value));

        assertEquals("th", values.get(AdaptationDimensions.LANGUAGE));
        assertEquals("concise", values.get(AdaptationDimensions.RESPONSE_LENGTH));
        assertEquals("bullets", values.get(AdaptationDimensions.RESPONSE_FORMAT));
        assertEquals("simple", values.get(AdaptationDimensions.EXPLANATION_LEVEL));
        assertEquals("casual", values.get(AdaptationDimensions.TONE));
        assertTrue(detector.detect("ลุย").isEmpty());
    }

    @Test
    void understandsNegatedStyleRequests() {
        Map<String, String> values = detector.detect("ไม่ต้องตอบสั้น และไม่ต้องเป็นข้อ")
                .stream().collect(Collectors.toMap(
                        AdaptationObservation::dimension, AdaptationObservation::value));

        assertEquals("detailed", values.get(AdaptationDimensions.RESPONSE_LENGTH));
        assertEquals("prose", values.get(AdaptationDimensions.RESPONSE_FORMAT));
    }
}
