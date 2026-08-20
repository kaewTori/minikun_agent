package com.minikun.personality.learning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Conservative, deterministic detector for response-style requests. */
public final class ResponsePreferenceDetector {
    public List<AdaptationObservation> detect(String message) {
        if (message == null || message.isBlank()) return List.of();
        String text = message.toLowerCase(Locale.ROOT);
        Map<String, AdaptationObservation> observations = new LinkedHashMap<>();

        inferLanguage(text, observations);
        if (contains(text, "ตอบเป็นภาษาไทย", "ใช้ภาษาไทย", "in thai")) {
            explicit(observations, AdaptationDimensions.LANGUAGE, "th");
        } else if (contains(text, "ตอบเป็นภาษาอังกฤษ", "ใช้ภาษาอังกฤษ", "in english")) {
            explicit(observations, AdaptationDimensions.LANGUAGE, "en");
        }

        if (contains(text, "ไม่ต้องตอบสั้น", "อย่าสรุปสั้น", "not concise")) {
            explicit(observations, AdaptationDimensions.RESPONSE_LENGTH, "detailed");
        } else if (contains(text, "ไม่ต้องละเอียด", "ไม่ต้องยาว", "too much detail")) {
            explicit(observations, AdaptationDimensions.RESPONSE_LENGTH, "concise");
        } else if (contains(text, "ตอบสั้น", "สั้นๆ", "สั้น ๆ", "กระชับ", "concise", "brief")) {
            explicit(observations, AdaptationDimensions.RESPONSE_LENGTH, "concise");
        } else if (contains(text, "ละเอียด", "ลงลึก", "อธิบายเพิ่ม", "deep dive", "in detail")) {
            explicit(observations, AdaptationDimensions.RESPONSE_LENGTH, "detailed");
        }

        if (contains(text, "ไม่ต้องเป็นข้อ", "ไม่ต้อง bullet", "no bullets")) {
            explicit(observations, AdaptationDimensions.RESPONSE_FORMAT, "prose");
        } else if (contains(text, "ทีละขั้น", "เป็นขั้นตอน", "step by step")) {
            explicit(observations, AdaptationDimensions.RESPONSE_FORMAT, "steps");
        } else if (contains(text, "เป็นข้อ", "bullet", "ลิสต์", "list format")) {
            explicit(observations, AdaptationDimensions.RESPONSE_FORMAT, "bullets");
        }

        if (contains(text, "เข้าใจง่าย", "ภาษาง่าย", "สำหรับมือใหม่", "simple terms", "beginner")) {
            explicit(observations, AdaptationDimensions.EXPLANATION_LEVEL, "simple");
        } else if (contains(text, "เชิงเทคนิค", "ลงรายละเอียดโค้ด", "technical", "implementation detail")) {
            explicit(observations, AdaptationDimensions.EXPLANATION_LEVEL, "technical");
        }

        if (contains(text, "เป็นกันเอง", "คุยสบาย", "casual")) {
            explicit(observations, AdaptationDimensions.TONE, "casual");
        } else if (contains(text, "เป็นทางการ", "สุภาพทางการ", "professional tone", "formal tone")) {
            explicit(observations, AdaptationDimensions.TONE, "professional");
        }
        return new ArrayList<>(observations.values());
    }

    private void inferLanguage(String text, Map<String, AdaptationObservation> observations) {
        long thai = text.codePoints().filter(code -> code >= 0x0E00 && code <= 0x0E7F).count();
        long latin = text.codePoints().filter(code -> code >= 'a' && code <= 'z').count();
        if (thai >= 4 && thai >= latin) {
            observations.put(AdaptationDimensions.LANGUAGE,
                    new AdaptationObservation(AdaptationDimensions.LANGUAGE, "th", .25, false));
        } else if (latin >= 8 && thai == 0) {
            observations.put(AdaptationDimensions.LANGUAGE,
                    new AdaptationObservation(AdaptationDimensions.LANGUAGE, "en", .25, false));
        }
    }

    private void explicit(Map<String, AdaptationObservation> values, String dimension, String value) {
        values.put(dimension, new AdaptationObservation(dimension, value, 1.5, true));
    }

    private boolean contains(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }
}
