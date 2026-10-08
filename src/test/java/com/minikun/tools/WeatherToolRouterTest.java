package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        assertTrue(result.get().content().contains("ไม่มีข้อมูลรายชั่วโมง"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"คืนนี้ฝนจะตกที่กรุงเทพช่วงกี่โมง", "ฝนที่กรุงเทพคืนนี้จะตกกี่โมง",
            "คืนนี้กรุงเทพฝนจะตกกี่โมง", "When will it rain in กรุงเทพ tonight?",
            "คืนนี้ฝนจะตกช่วงกี่โมง ที่ กรุงเทพ"})
    void routesTonightRainTimingAndPassesHourlyEvidenceWithSourceAndTimezone(String question) {
        WeatherToolRouter router = new WeatherToolRouter(new DefaultToolExecutor(
                new DefaultToolRegistry(java.util.List.of(new WeatherForecastTool(request -> {
                    assertEquals("กรุงเทพ", request.location());
                    assertEquals("tonight", request.when());
                    return new WeatherReport("กรุงเทพ", "Thailand", 13.75, 100.5, "Asia/Bangkok",
                            "2026-10-06", 30.0, null, null, null, 2, "cloudy",
                            27.0, 33.0, 100, null, "", "", Instant.parse("2026-10-06T15:15:00Z"),
                            "[Open-Meteo](https://open-meteo.com/)", java.util.List.of(
                                    new WeatherReport.HourlyForecast("2026-10-06T22:00", "2026-10-06T23:00",
                                            90, 2.0, "thunderstorm", 35.0),
                                    new WeatherReport.HourlyForecast("2026-10-06T23:00", "2026-10-07T00:00",
                                            null, null, "unknown", null)));
                })))));

        var result = router.route(question, new ConversationId("conversation"));

        assertTrue(result.orElseThrow().success());
        String content = result.get().content();
        assertTrue(content.contains("2026-10-06T22:00 – 2026-10-06T23:00"));
        assertTrue(content.contains("90%"));
        assertTrue(content.contains("thunderstorm; ลมกระโชก 35.0"));
        assertTrue(content.contains("2026-10-06T23:00 – 2026-10-07T00:00: unknown"));
        assertTrue(content.contains("Asia/Bangkok"));
        assertTrue(content.contains("2026-10-06T22:15+07:00"));
        assertTrue(content.contains("[Open-Meteo](https://open-meteo.com/)"));
        assertTrue(content.contains("do not invent exact onset times"));
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
