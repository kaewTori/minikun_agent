package com.minikun.weather;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

public final class WeatherAlertFormatter {
    private WeatherAlertFormatter() {
    }

    public static String format(WeatherReport report) {
        StringBuilder message = new StringBuilder("พี่สาวครับ รายงานสภาพอากาศวันนี้ของ ")
                .append(report.location()).append(" (ในวันที่ ").append(report.requestedDate()).append(")")
                .append("\nสภาพอากาศ: ").append(report.currentWeatherDescription());
        append(message, "อุณหภูมิปัจจุบัน", report.currentTemperatureCelsius(), " °C");
        append(message, "ช่วงอุณหภูมิวันนี้", report.dailyTemperatureMinCelsius(), "–"
                + value(report.dailyTemperatureMaxCelsius()) + " °C", true);
        append(message, "โอกาสฝนตก", report.dailyPrecipitationProbabilityPercent(), "%");
        append(message, "ปริมาณฝน", report.dailyPrecipitationMm(), " mm");
        append(message, "ลม", report.currentWindKmh(), " km/h");
        if (report.sunrise() != null && !report.sunrise().isBlank()) {
            message.append("\nพระอาทิตย์ขึ้น: ").append(report.sunrise());
        }
        if (report.sunset() != null && !report.sunset().isBlank()) {
            message.append("\nพระอาทิตย์ตก: ").append(report.sunset());
        }
        message.append("\nแหล่งข้อมูล: ").append(report.source());
        return message.toString();
    }

    public static int priority(WeatherReport report) {
        String description = report.currentWeatherDescription() == null
                ? "" : report.currentWeatherDescription().toLowerCase(Locale.ROOT);
        if (report.dailyPrecipitationProbabilityPercent() != null
                && report.dailyPrecipitationProbabilityPercent() >= 80) {
            return 4;
        }
        return description.contains("storm") || description.contains("thunder") || description.contains("ฟ้าคะนอง")
                ? 4 : 3;
    }

    public static String tags(WeatherReport report) {
        String description = report.currentWeatherDescription() == null
                ? "" : report.currentWeatherDescription().toLowerCase(Locale.ROOT);
        if (description.contains("rain") || description.contains("ฝน")) {
            return "cloud_with_rain,weather";
        }
        if (description.contains("storm") || description.contains("ฟ้าคะนอง")) {
            return "thunder_cloud_and_rain,warning,weather";
        }
        return "sunny,weather";
    }

    private static void append(StringBuilder message, String label, Object value, String suffix) {
        if (value != null) {
            message.append("\n").append(label).append(": ").append(value).append(suffix);
        }
    }

    private static void append(StringBuilder message, String label, Object value, String suffix, boolean alreadyFormatted) {
        if (value != null) {
            message.append("\n").append(label).append(": ").append(value).append(suffix);
        }
    }

    private static String value(Double value) {
        return value == null ? "-" : value.toString();
    }
}
