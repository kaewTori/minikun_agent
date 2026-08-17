package com.minikun.weather;

import java.util.Objects;

public record WeatherRequest(String location, String when, String countryCode) {
    public WeatherRequest {
        Objects.requireNonNull(location, "location must not be null");
        if (location.isBlank()) {
            throw new IllegalArgumentException("location must not be blank");
        }
        when = Objects.requireNonNullElse(when, "").trim();
        countryCode = Objects.requireNonNullElse(countryCode, "").trim().toUpperCase();
        if (!countryCode.isBlank() && !countryCode.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("country code must be ISO-3166 alpha-2");
        }
    }
}
