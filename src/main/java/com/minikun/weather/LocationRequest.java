package com.minikun.weather;

import java.util.Locale;
import java.util.Objects;

/** User input for place resolution. */
public record LocationRequest(String query, String countryCode) {
    public LocationRequest {
        query = Objects.requireNonNullElse(query, "").trim();
        countryCode = Objects.requireNonNullElse(countryCode, "").trim().toUpperCase(Locale.ROOT);
        if (query.isBlank()) {
            throw new IllegalArgumentException("location query is required");
        }
        if (!countryCode.isBlank() && !countryCode.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("country_code must be an ISO-3166 alpha-2 code");
        }
    }
}
