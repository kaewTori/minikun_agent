package com.minikun.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/** Open-Meteo forecast adapter using the shared location resolver. */
public final class OpenMeteoWeatherProvider implements WeatherProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenMeteoWeatherProvider.class);
    private static final String SOURCE = "[Open-Meteo](https://open-meteo.com/) (CC BY 4.0)";

    private final LocationResolver locationResolver;
    private final RestClient forecastClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OpenMeteoWeatherProvider(
            LocationResolver locationResolver,
            RestClient forecastClient,
            ObjectMapper objectMapper,
            Clock clock) {
        this.locationResolver = Objects.requireNonNull(locationResolver, "location resolver must not be null");
        this.forecastClient = Objects.requireNonNull(forecastClient, "forecast client must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public WeatherReport forecast(WeatherRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Instant started = clock.instant();
        try {
            LocationResult location = request.latitude() == null
                    ? locationResolver.resolve(new LocationRequest(request.location(), request.countryCode()))
                    : new LocationResult(request.location(), "", "", "", "", request.latitude(),
                            request.longitude(), "UTC", clock.instant(), "Device location");
            double latitude = location.latitude();
            double longitude = location.longitude();
            URIRequest forecastRequest = forecastUri(latitude, longitude);
            String body = forecastClient.get().uri(forecastRequest.uri()).retrieve().body(String.class);
            String timezone = request.latitude() == null ? location.timezone()
                    : forecastTimezone(body);
            ZoneId zone = ZoneId.of(timezone);
            LocalDate requestedDate = requestedDate(request.when(), clock.instant(), zone);
            WeatherReport report = parseReport(location, body, requestedDate, latitude, longitude, timezone,
                    request.when());
            LOGGER.info("process=weather event=completed location={} requested_date={} duration_ms={}",
                    report.location(), report.requestedDate(), Duration.between(started, clock.instant()).toMillis());
            return report;
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException("weather provider returned HTTP "
                    + exception.getStatusCode().value(), exception);
        } catch (RuntimeException exception) {
            LOGGER.warn("process=weather event=failed location={} failure_type={} message={}",
                    request.location(), exception.getClass().getSimpleName(), exception.getMessage());
            throw exception;
        }
    }

    private String forecastTimezone(String body) {
        try {
            return objectMapper.readTree(body).path("timezone").asText("");
        } catch (Exception exception) {
            throw new IllegalStateException("weather forecast response is invalid", exception);
        }
    }

    private URIRequest forecastUri(double latitude, double longitude) {
        return new URIRequest(UriComponentsBuilder.fromPath("/v1/forecast")
                .queryParam("latitude", latitude)
                .queryParam("longitude", longitude)
                .queryParam("current",
                        "temperature_2m,apparent_temperature,precipitation,wind_speed_10m,weather_code")
                .queryParam("daily",
                        "weather_code,temperature_2m_min,temperature_2m_max,precipitation_probability_max,"
                                + "precipitation_sum,sunrise,sunset")
                .queryParam("hourly", "precipitation_probability,precipitation,weather_code,wind_gusts_10m")
                .queryParam("forecast_days", 16)
                .queryParam("timezone", "auto")
                .queryParam("temperature_unit", "celsius")
                .queryParam("wind_speed_unit", "kmh")
                .queryParam("precipitation_unit", "mm")
                .build()
                .toUri());
    }

    private WeatherReport parseReport(
            LocationResult location,
            String body,
            LocalDate requestedDate,
            double latitude,
            double longitude,
            String timezone,
            String when) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode current = root.path("current");
            JsonNode daily = root.path("daily");
            List<String> dates = strings(daily.path("time"));
            int index = dates.indexOf(requestedDate.toString());
            if (index < 0) {
                throw new IllegalArgumentException("weather forecast is unavailable for " + requestedDate);
            }
            return new WeatherReport(
                    location.name(), location.country(), latitude, longitude,
                    timezone, requestedDate.toString(), nullableNumber(current, "temperature_2m"),
                    nullableNumber(current, "apparent_temperature"), nullableNumber(current, "precipitation"),
                    nullableNumber(current, "wind_speed_10m"), integerOrNull(current, "weather_code"),
                    weatherDescription(integer(current, "weather_code", -1)),
                    nullableNumberAt(daily.path("temperature_2m_min"), index),
                    nullableNumberAt(daily.path("temperature_2m_max"), index),
                    nullableIntegerAt(daily.path("precipitation_probability_max"), index),
                    nullableNumberAt(daily.path("precipitation_sum"), index), stringAt(daily.path("sunrise"), index),
                    stringAt(daily.path("sunset"), index), clock.instant(), SOURCE,
                    hourlyForecast(root.path("hourly"), requestedDate, when, ZoneId.of(timezone)));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("weather forecast response is invalid", exception);
        }
    }

    private List<WeatherReport.HourlyForecast> hourlyForecast(
            JsonNode hourly, LocalDate date, String when, ZoneId zone) {
        String normalized = when.trim().toLowerCase(Locale.ROOT);
        boolean night = List.of("tonight", "คืนนี้", "tomorrow evening", "tomorrow night", "พรุ่งนี้เย็น",
                "พรุ่งนี้คืน").contains(normalized);
        LocalDateTime start = night ? date.atTime(18, 0) : date.atStartOfDay();
        LocalDateTime end = night ? date.plusDays(1).atTime(6, 0) : date.plusDays(1).atStartOfDay();
        LocalDateTime now = clock.instant().atZone(zone).toLocalDateTime();
        if (now.isAfter(start)) start = now;
        List<WeatherReport.HourlyForecast> result = new ArrayList<>();
        List<String> times = strings(hourly.path("time"));
        for (int index = 0; index < times.size(); index++) {
            LocalDateTime to = LocalDateTime.parse(times.get(index));
            // Open-Meteo precipitation and gusts describe the preceding hour.
            if (!to.isAfter(start) || to.isAfter(end)) continue;
            result.add(new WeatherReport.HourlyForecast(to.minusHours(1).toString(), to.toString(),
                    nullableIntegerAt(hourly.path("precipitation_probability"), index),
                    nullableNumberAt(hourly.path("precipitation"), index),
                    weatherDescription(integerAt(hourly.path("weather_code"), index, -1)),
                    nullableNumberAt(hourly.path("wind_gusts_10m"), index)));
        }
        return result;
    }

    private LocalDate requestedDate(String when, Instant now, ZoneId zone) {
        String normalized = Objects.requireNonNullElse(when, "").trim().toLowerCase(Locale.ROOT);
        LocalDate today = now.atZone(zone).toLocalDate();
        if (normalized.isBlank() || normalized.equals("now") || normalized.equals("current")
                || normalized.equals("today") || normalized.equals("tonight")
                || normalized.equals("วันนี้") || normalized.equals("ตอนนี้") || normalized.equals("คืนนี้")) {
            return today;
        }
        if (normalized.equals("tomorrow") || normalized.equals("tomorrow morning")
                || normalized.equals("tomorrow evening") || normalized.equals("tomorrow night")
                || normalized.equals("พรุ่งนี้") || normalized.equals("พรุ่งนี้เช้า")
                || normalized.equals("พรุ่งนี้เย็น") || normalized.equals("พรุ่งนี้คืน")) {
            return today.plusDays(1);
        }
        try {
            return LocalDate.parse(normalized);
        } catch (Exception exception) {
            throw new IllegalArgumentException("unsupported weather time: " + when);
        }
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(value -> values.add(value.asText()));
        }
        return values;
    }

    private String stringAt(JsonNode node, int index) {
        return node.isArray() && index < node.size() ? node.get(index).asText("") : "";
    }

    private Double nullableNumber(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asDouble() : null;
    }

    private Double nullableNumberAt(JsonNode node, int index) {
        return node.isArray() && index < node.size() && node.get(index).isNumber()
                ? node.get(index).asDouble() : null;
    }

    private Integer nullableIntegerAt(JsonNode node, int index) {
        return node.isArray() && index < node.size() && node.get(index).isNumber()
                ? node.get(index).asInt() : null;
    }

    private Integer integerOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asInt() : null;
    }

    private int integerAt(JsonNode node, int index, int fallback) {
        return node.isArray() && index < node.size() && node.get(index).isNumber()
                ? node.get(index).asInt() : fallback;
    }

    private int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asInt() : fallback;
    }

    private String weatherDescription(int code) {
        return switch (code) {
            case 0 -> "clear sky";
            case 1, 2, 3 -> "mainly clear or cloudy";
            case 45, 48 -> "fog";
            case 51, 53, 55, 56, 57 -> "drizzle";
            case 61, 63, 65, 66, 67 -> "rain";
            case 71, 73, 75, 77 -> "snow";
            case 80, 81, 82 -> "rain showers";
            case 85, 86 -> "snow showers";
            case 95, 96, 99 -> "thunderstorm";
            default -> "unknown";
        };
    }

    private record URIRequest(java.net.URI uri) {
    }
}
