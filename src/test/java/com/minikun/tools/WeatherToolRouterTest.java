package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.weather.DeviceLocation;
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
                "แล้ว พรุ่งนี้กรุงเทพอากาศเป็นอย่างไร",
                new ConversationId("conversation"));

        assertTrue(result.isPresent());
        assertTrue(result.get().success());
        assertTrue(result.get().content().contains("กรุงเทพ"));
        assertTrue(result.get().content().contains("70%"));
    }

    @Test
    void asksForLocationInsteadOfSearchingWeb() {
        WeatherToolRouter router = new WeatherToolRouter(new DefaultToolExecutor(
                new DefaultToolRegistry(java.util.List.of(new CalculatorAddTool()))));

        var result = router.route("อยากรู้สภาพอากาศวันพรุ่งนี้จังเลย", new ConversationId("conversation"));
        assertTrue(result.isPresent());
        assertTrue(result.get().finalResponse());
        assertTrue(result.get().content().contains("ชื่อเมือง"));
    }

    @Test
    void routesCurrentLocationUsingDeviceCoordinates() {
        WeatherToolRouter router = new WeatherToolRouter(new DefaultToolExecutor(
                new DefaultToolRegistry(java.util.List.of(new WeatherForecastTool(request -> {
                    assertEquals(13.75, request.latitude());
                    assertEquals(100.5, request.longitude());
                    return new WeatherReport("ตำแหน่งปัจจุบัน", "", 13.75, 100.5, "Asia/Bangkok",
                            "2026-09-29", 30.0, null, null, null, null, "เมฆบางส่วน",
                            27.0, 33.0, 40, null, "", "", Instant.now(), "test");
                })))));

        var result = router.route("อากาศที่นี่เป็นยังไง", new ConversationId("conversation"), "owner",
                new DeviceLocation(13.75, 100.5, 10.0, System.currentTimeMillis()));

        assertTrue(result.isPresent());
        assertTrue(result.get().success());
        assertTrue(result.get().content().contains("40%"));
    }
}
