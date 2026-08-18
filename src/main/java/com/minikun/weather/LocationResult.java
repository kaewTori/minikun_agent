package com.minikun.weather;

import java.time.Instant;
import java.util.Objects;

/** Canonical place returned by the geocoding provider. */
public record LocationResult(
        String name,
        String country,
        String countryCode,
        String admin1,
        String admin2,
        double latitude,
        double longitude,
        String timezone,
        Instant retrievedAt,
        String source) {
    public LocationResult {
        Objects.requireNonNull(name, "location name must not be null");
        Objects.requireNonNull(country, "country must not be null");
        Objects.requireNonNull(countryCode, "country code must not be null");
        Objects.requireNonNull(admin1, "admin1 must not be null");
        Objects.requireNonNull(admin2, "admin2 must not be null");
        Objects.requireNonNull(timezone, "timezone must not be null");
        Objects.requireNonNull(retrievedAt, "retrieved at must not be null");
        Objects.requireNonNull(source, "source must not be null");
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("latitude must be between -90 and 90");
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("longitude must be between -180 and 180");
        }
    }
}
