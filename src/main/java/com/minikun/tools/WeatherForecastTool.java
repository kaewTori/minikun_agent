package com.minikun.tools;

import com.minikun.weather.WeatherProvider;
import com.minikun.weather.WeatherReport;
import com.minikun.weather.WeatherRequest;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Structured weather capability; location and time are resolved at runtime. */
@Component
public final class WeatherForecastTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "weather.get_forecast",
            "Get current weather or a forecast for a user-provided location and time. "
                    + "Use this for weather, rain, temperature, wind, umbrella, outdoor-plan, "
                    + "and forecast questions. Do not guess coordinates.",
            Map.of(
                    "location", new ToolParameter("location", ToolParameterType.STRING, true,
                            "Place name, address, postal code, or coordinates provided by the user."),
                    "when", new ToolParameter("when", ToolParameterType.STRING, false,
                            "Current, today, tomorrow, or an ISO date such as 2026-08-20."),
                    "country_code", new ToolParameter("country_code", ToolParameterType.STRING, false,
                            "Optional ISO-3166 alpha-2 country code to disambiguate a place.")));

    private final WeatherProvider provider;

    public WeatherForecastTool(WeatherProvider provider) {
        this.provider = Objects.requireNonNull(provider, "weather provider must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override public boolean requiresExplicitConfirmation(Map<String, Object> arguments) { return false; }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        String location = text(arguments, "location");
        if (location.isBlank()) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "weather location is required");
        }
        try {
            WeatherReport report = provider.forecast(new WeatherRequest(
                    location, text(arguments, "when"), text(arguments, "country_code")));
            return ToolResult.success(report);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "weather data is temporarily unavailable");
        }
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().trim();
    }
}
