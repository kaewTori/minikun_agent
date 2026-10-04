package com.minikun.tools;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.weather.DeviceLocation;
import com.minikun.weather.WeatherReport;

/**
 * Routes unambiguous weather questions to the weather tool without relying on
 * the language model to decide whether it should emit a native tool call.
 */
@Component
public final class WeatherToolRouter implements ToolRequestRouter {
    private final ToolExecutor executor;

    public WeatherToolRouter(ToolExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "tool executor must not be null");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        return route(userText, conversationId, "", null);
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId,
            DeviceLocation deviceLocation) {
        if (userText == null || userText.isBlank() || conversationId == null || !isWeatherQuestion(userText)) {
            return Optional.empty();
        }
        boolean currentLocation = refersToCurrentLocation(userText);
        Optional<String> locationValue = currentLocation ? Optional.empty() : location(userText);
        if (locationValue.isEmpty() && !usable(deviceLocation)) {
            return Optional.of(ToolEvidence.finalFailed("weather.get_forecast",
                    "บอกชื่อเมืองหรือเปิด/อัปเดตตำแหน่งแล้วถามสภาพอากาศอีกครั้งได้ไหมครับ"));
        }
        String location = locationValue.orElse("ตำแหน่งปัจจุบัน");

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("location", location);
        if (locationValue.isEmpty()) {
            arguments.put("latitude", deviceLocation.latitude());
            arguments.put("longitude", deviceLocation.longitude());
        }
        time(userText).ifPresent(value -> arguments.put("when", value));
        String callId = "weather-route-" + UUID.randomUUID();
        ToolResult result = executor.execute(
                new ToolCallContext(conversationId, callId),
                new ToolCall(callId, "weather.get_forecast", arguments));
        if (!result.success()) {
            return Optional.of(ToolEvidence.failed("weather.get_forecast",
                    "ขออภัยครับ ตอนนี้ยังดึงข้อมูลสภาพอากาศของ " + location
                            + " ไม่สำเร็จ: " + result.error()));
        }
        if (!(result.value() instanceof WeatherReport report)) {
            return Optional.of(ToolEvidence.failed("weather.get_forecast",
                    "ขออภัยครับ ข้อมูลสภาพอากาศที่ได้รับมีรูปแบบไม่ถูกต้อง"));
        }
        return Optional.of(ToolEvidence.verified("weather.get_forecast", format(report)));
    }

    private boolean refersToCurrentLocation(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return java.util.List.of("ที่นี่", "แถวนี้", "ตรงนี้", "ใกล้ฉัน", "ใกล้ผม", "ใกล้เรา",
                "ที่เราอยู่", "ที่ราอยู่", "ตำแหน่งปัจจุบัน", "current location", "near me", "here")
                .stream().anyMatch(normalized::contains);
    }

    private boolean usable(DeviceLocation location) {
        if (location == null || location.capturedAt() == null) return false;
        long age = System.currentTimeMillis() - location.capturedAt();
        return age >= -120_000 && age <= 600_000;
    }

    private boolean isWeatherQuestion(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("อากาศ")
                || normalized.contains("พยากรณ์")
                || normalized.contains("ฝน")
                || normalized.contains("อุณหภูมิ")
                || normalized.contains("ร่ม")
                || normalized.contains("weather")
                || normalized.contains("forecast")
                || normalized.contains("rain")
                || normalized.contains("temperature");
    }

    private Optional<String> time(String text) {
        for (int index = 0; index + 10 <= text.length(); index++) {
            String candidate = text.substring(index, index + 10);
            if (candidate.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return Optional.of(candidate);
            }
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        if (normalized.contains("พรุ่งนี้") || normalized.contains("tomorrow")) {
            return Optional.of("tomorrow");
        }
        if (normalized.contains("วันนี้") || normalized.contains("today")) {
            return Optional.of("today");
        }
        return Optional.empty();
    }

    private Optional<String> location(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        int markerEnd = -1;
        for (String marker : java.util.List.of("ที่", "ใน", "แถว", "ของ", "at ", "in ", "near ")) {
            int markerIndex = normalized.lastIndexOf(marker);
            if (markerIndex >= 0) {
                markerEnd = Math.max(markerEnd, markerIndex + marker.length());
            }
        }
        if (markerEnd < 0) {
            return temporalWeatherLocation(text);
        }

        String candidate = text.substring(markerEnd).trim();
        String candidateNormalized = candidate.toLowerCase(Locale.ROOT);
        int end = candidate.length();
        for (String suffix : java.util.List.of(
                "จังเลย", "หน่อย", "ครับ", "ค่ะ", "คะ", "นะ", "ไหม", "ได้ไหม",
                "วันนี้", "พรุ่งนี้", "ตอนนี้")) {
            int suffixIndex = candidateNormalized.indexOf(suffix);
            if (suffixIndex >= 0) {
                end = Math.min(end, suffixIndex);
            }
        }
        for (char punctuation : new char[] {'?', '!', '.', '。', '！', '？', ','}) {
            int punctuationIndex = candidate.indexOf(punctuation);
            if (punctuationIndex >= 0) {
                end = Math.min(end, punctuationIndex);
            }
        }
        String location = candidate.substring(0, end).trim();
        return location.isBlank() ? Optional.empty() : Optional.of(location);
    }

    private Optional<String> temporalWeatherLocation(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String timeWord : java.util.List.of("พรุ่งนี้", "วันนี้", "tomorrow", "today")) {
            int timeEnd = normalized.indexOf(timeWord);
            if (timeEnd < 0) {
                continue;
            }
            timeEnd += timeWord.length();
            for (String weatherWord : java.util.List.of("อากาศ", "weather", "forecast")) {
                int weatherStart = normalized.indexOf(weatherWord, timeEnd);
                if (weatherStart < 0) {
                    continue;
                }
                String location = text.substring(timeEnd, weatherStart).trim();
                location = location.replaceFirst("^(?iu)(ที่|ใน|แถว|ของ)\\s*", "").trim();
                if (!location.isBlank()) {
                    return Optional.of(location);
                }
            }
        }
        return Optional.empty();
    }

    private String format(WeatherReport report) {
        StringBuilder content = new StringBuilder()
                .append("พยากรณ์อากาศสำหรับ ").append(report.location())
                .append(" วันที่ ").append(report.requestedDate()).append("\n");
        append(content, "สภาพอากาศ", report.currentWeatherDescription());
        appendRange(content, report.dailyTemperatureMinCelsius(), report.dailyTemperatureMaxCelsius());
        if (report.dailyPrecipitationProbabilityPercent() != null) {
            append(content, "โอกาสฝนตก", report.dailyPrecipitationProbabilityPercent() + "%");
        }
        if (report.dailyPrecipitationMm() != null) {
            append(content, "ปริมาณฝนคาดการณ์", report.dailyPrecipitationMm() + " มม.");
        }
        if (report.currentWindKmh() != null) {
            append(content, "ลม", report.currentWindKmh() + " กม./ชม.");
        }
        content.append("แหล่งข้อมูล: ").append(report.source());
        return content.toString();
    }

    private void appendRange(StringBuilder content, Double minimum, Double maximum) {
        if (minimum != null && maximum != null) {
            append(content, "อุณหภูมิ", minimum + "–" + maximum + "°C");
        }
    }

    private void append(StringBuilder content, String label, Object value) {
        if (value != null && !value.toString().isBlank()) {
            content.append(label).append(": ").append(value).append("\n");
        }
    }
}
