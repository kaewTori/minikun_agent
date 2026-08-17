package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.weather.WeatherReport;

class WeatherToolRouterTest {
    @Test
    void routesThaiWeatherQuestionToWeatherToolWithoutModel() {
        WeatherToolRouter router = new WeatherToolRouter(new DefaultToolExecutor(
                new DefaultToolRegistry(java.util.List.of(new WeatherForecastTool(request ->
                        new WeatherReport("กรุงเทพ", "Thailand", 13.75, 100.50, "Asia/Bangkok",
                                "2026-08-19", 30.0, 34.0, 1.0, 12.0, 61, "rain",
                                28.0, 35.0, 70, 4.0, "06:00", "18:40", Instant.now(), "test"))))));

        var result = router.route(
                "มินิคุงเราอยากรู้สภาพอากาศวันพรุ่งนี้ที่กรุงเทพจังเลย",
                new ConversationId("conversation"));

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("กรุงเทพ"));
        assertTrue(result.get().contains("70%"));
    }

    @Test
    void doesNotRouteWeatherQuestionWithoutLocation() {
        WeatherToolRouter router = new WeatherToolRouter(new DefaultToolExecutor(
                new DefaultToolRegistry(java.util.List.of(new CalculatorAddTool()))));

        assertEquals(java.util.Optional.empty(), router.route(
                "อยากรู้สภาพอากาศวันพรุ่งนี้จังเลย", new ConversationId("conversation")));
    }
}
