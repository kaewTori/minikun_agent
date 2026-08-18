package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.weather.LocationRequest;
import com.minikun.weather.LocationResolver;
import com.minikun.weather.LocationResult;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocationResolveToolTest {
    @Test
    void passesQueryAndCountryCodeToResolver() {
        LocationRequest[] captured = new LocationRequest[1];
        LocationResolver resolver = request -> {
            captured[0] = request;
            return new LocationResult("London", "United Kingdom", "GB", "England", "",
                    51.5074, -0.1278, "Europe/London", Instant.parse("2026-08-19T00:00:00Z"), "test");
        };
        LocationResolveTool tool = new LocationResolveTool(resolver);

        ToolResult result = tool.execute(
                new ToolCallContext(new ConversationId("location"), "call"),
                Map.of("query", "ลอนดอน", "country_code", "GB"));

        assertTrue(result.success());
        assertEquals("ลอนดอน", captured[0].query());
        assertEquals("GB", captured[0].countryCode());
        assertEquals("London", ((LocationResult) result.value()).name());
    }

    @Test
    void rejectsMissingQuery() {
        LocationResolveTool tool = new LocationResolveTool(request -> {
            throw new AssertionError("resolver must not be called");
        });

        ToolResult result = tool.execute(
                new ToolCallContext(new ConversationId("location"), "call"), Map.of());

        assertTrue(!result.success());
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, result.errorCode());
    }
}
