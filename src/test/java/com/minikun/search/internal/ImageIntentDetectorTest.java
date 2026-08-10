package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ImageIntentDetectorTest {
    private final ImageIntentDetector detector = new ImageIntentDetector();

    @Test
    void detectsExplicitThaiImageRequests() {
        List<String> queries = List.of(
                "หารูปแมว",
                "ขอรูปแมว",
                "ขอภาพของภูเขา",
                "แสดงรูป Tesla Model 3",
                "อยากดูภาพ Eiffel Tower");

        queries.forEach(query -> assertTrue(detector.detects(query), query));
    }

    @Test
    void detectsExplicitEnglishImageRequests() {
        List<String> queries = List.of(
                "find images of cats",
                "show me a photo of Tesla Model 3",
                "pictures of Eiffel Tower",
                "what does this place look like");

        queries.forEach(query -> assertTrue(detector.detects(query), query));
    }

    @Test
    void rejectsStructuralAndAmbiguousImageWords() {
        List<String> queries = List.of(
                "รูปแบบการทำงาน",
                "รูปแบบ design pattern",
                "ภาพรวมระบบ",
                "อธิบายรูปแบบ architecture",
                "architecture explanation");

        queries.forEach(query -> assertFalse(detector.detects(query), query));
    }

    @Test
    void isDeterministicAndFailsClosedForEmptyInput() {
        assertFalse(detector.detects(null));
        assertFalse(detector.detects(" "));
        assertTrue(detector.detects("หารูปแมว") == detector.detects("หารูปแมว"));
    }
}