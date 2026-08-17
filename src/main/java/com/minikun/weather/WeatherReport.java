package com.minikun.weather;

import java.time.Instant;
import java.util.Objects;

public record WeatherReport(
        String location,
        String country,
        double latitude,
        double longitude,
        String timezone,
        String requestedDate,
        Double currentTemperatureCelsius,
        Double currentApparentTemperatureCelsius,
        Double currentPrecipitationMm,
        Double currentWindKmh,
        Integer currentWeatherCode,
        String currentWeatherDescription,
        Double dailyTemperatureMinCelsius,
        Double dailyTemperatureMaxCelsius,
        Integer dailyPrecipitationProbabilityPercent,
        Double dailyPrecipitationMm,
        String sunrise,
        String sunset,
        Instant retrievedAt,
        String source) {
    public WeatherReport {
        Objects.requireNonNull(location, "location must not be null");
        Objects.requireNonNull(timezone, "timezone must not be null");
        Objects.requireNonNull(requestedDate, "requested date must not be null");
        Objects.requireNonNull(retrievedAt, "retrieved at must not be null");
        Objects.requireNonNull(source, "source must not be null");
    }
}
