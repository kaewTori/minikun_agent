package com.minikun.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class WeatherAlertFormatterTest {
    @Test
    void formatsWeatherMessageAndRaisesPriorityForHeavyRain() {
        WeatherReport report = new WeatherReport(
                "กรุงเทพ", "Thailand", 13.75, 100.50, "Asia/Bangkok", "2026-08-19",
                30.0, 34.0, 3.0, 12.0, 95, "ฝนฟ้าคะนอง",
                26.0, 33.0, 85, 22.0, "06:00", "18:40", Instant.now(), "Open-Meteo");

        String message = WeatherAlertFormatter.format(report);

        assertTrue(message.contains("กรุงเทพ"));
        assertTrue(message.contains("โอกาสฝนตก: 85%"));
        assertEquals(4, WeatherAlertFormatter.priority(report));
        assertTrue(WeatherAlertFormatter.tags(report).contains("weather"));
    }
}
