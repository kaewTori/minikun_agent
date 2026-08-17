package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.weather.WeatherProvider;
import com.minikun.weather.WeatherReport;
import com.minikun.weather.WeatherRequest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeatherForecastToolTest {
    @Test
    void passesLocationAndTimeToProvider() {
        WeatherRequest[] captured = new WeatherRequest[1];
        WeatherProvider provider = request -> {
            captured[0] = request;
            return new WeatherReport("Chiang Mai", "Thailand", 18.79, 98.98, "Asia/Bangkok",
                    "2026-08-19", 28.0, 30.0, 0.0, 8.0, 2, "mainly clear or cloudy",
                    23.0, 32.0, 20, 0.2, "06:00", "18:45", Instant.now(), "test");
        };
        WeatherForecastTool tool = new WeatherForecastTool(provider);

        ToolResult result = tool.execute(
                new ToolCallContext(new com.minikun.agent.minikun_agent.conversation.ConversationId("test"), "call"),
                Map.of("location", "Chiang Mai", "when", "tomorrow", "country_code", "TH"));

        assertTrue(result.success());
        assertEquals("Chiang Mai", captured[0].location());
        assertEquals("tomorrow", captured[0].when());
        assertEquals("TH", captured[0].countryCode());
    }
}
