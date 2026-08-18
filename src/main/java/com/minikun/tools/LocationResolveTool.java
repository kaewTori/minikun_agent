package com.minikun.tools;

import com.minikun.weather.LocationRequest;
import com.minikun.weather.LocationResolver;
import com.minikun.weather.LocationResult;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Resolves a user-provided place into canonical geographic coordinates. */
@Component
public final class LocationResolveTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "location.resolve",
            "Resolve a place name, address, or city into a canonical location. "
                    + "Use this before weather or route tools when a place is ambiguous, "
                    + "written in another language, or needs coordinates and timezone. "
                    + "Do not guess coordinates.",
            Map.of(
                    "query", new ToolParameter("query", ToolParameterType.STRING, true,
                            "Place name, address, or city provided by the user."),
                    "country_code", new ToolParameter("country_code", ToolParameterType.STRING, false,
                            "Optional ISO-3166 alpha-2 country code to disambiguate a place.")));

    private final LocationResolver resolver;

    public LocationResolveTool(LocationResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "location resolver must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        String query = text(arguments, "query");
        if (query.isBlank()) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "location query is required");
        }
        try {
            LocationResult result = resolver.resolve(new LocationRequest(query, text(arguments, "country_code")));
            return ToolResult.success(result);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "location data is temporarily unavailable");
        }
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().trim();
    }
}
