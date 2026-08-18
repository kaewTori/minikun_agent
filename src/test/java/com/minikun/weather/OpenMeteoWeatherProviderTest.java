package com.minikun.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenMeteoWeatherProviderTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-18T05:00:00Z"), ZoneOffset.UTC);

    @Test
    void resolvesLocationAndMapsCurrentAndDailyForecast() {
        RestClient.Builder geocodingBuilder = RestClient.builder().baseUrl("https://geo.test");
        RestClient.Builder forecastBuilder = RestClient.builder().baseUrl("https://forecast.test");
        MockRestServiceServer geocodingServer = MockRestServiceServer.bindTo(geocodingBuilder).build();
        MockRestServiceServer forecastServer = MockRestServiceServer.bindTo(forecastBuilder).build();
        OpenMeteoWeatherProvider provider = new OpenMeteoWeatherProvider(
                new OpenMeteoLocationResolver(geocodingBuilder.build(), new ObjectMapper(), CLOCK),
                forecastBuilder.build(), new ObjectMapper(), CLOCK);

        geocodingServer.expect(requestTo("https://geo.test/v1/search?name=Chiang%20Mai&count=5&language=en&format=json&countryCode=TH"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"results":[{"name":"Chiang Mai","country":"Thailand","latitude":18.79,
                        "longitude":98.98,"timezone":"Asia/Bangkok"}]}
                        """, MediaType.APPLICATION_JSON));
        forecastServer.expect(requestTo("https://forecast.test/v1/forecast?latitude=18.79&longitude=98.98"
                + "&current=temperature_2m,apparent_temperature,precipitation,wind_speed_10m,weather_code"
                + "&daily=weather_code,temperature_2m_min,temperature_2m_max,precipitation_probability_max,"
                + "precipitation_sum,sunrise,sunset&forecast_days=16&timezone=auto&temperature_unit=celsius"
                + "&wind_speed_unit=kmh&precipitation_unit=mm"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"current":{"temperature_2m":28.4,"apparent_temperature":31.1,
                        "precipitation":0.2,"wind_speed_10m":9.5,"weather_code":2},
                        "daily":{"time":["2026-08-18","2026-08-19"],
                        "weather_code":[2,61],"temperature_2m_min":[23.0,22.0],
                        "temperature_2m_max":[31.0,29.0],"precipitation_probability_max":[20,80],
                        "precipitation_sum":[0.2,8.5],"sunrise":["2026-08-18T06:00","2026-08-19T06:00"],
                        "sunset":["2026-08-18T18:45","2026-08-19T18:45"]}}
                        """, MediaType.APPLICATION_JSON));

        WeatherReport report = provider.forecast(new WeatherRequest("Chiang Mai", "today", "TH"));

        assertEquals("Chiang Mai", report.location());
        assertEquals("2026-08-18", report.requestedDate());
        assertEquals(28.4, report.currentTemperatureCelsius());
        assertEquals(31.0, report.dailyTemperatureMaxCelsius());
        geocodingServer.verify();
        forecastServer.verify();
    }

    @Test
    void normalizesCommonThaiCityNameBeforeGeocoding() {
        RestClient.Builder geocodingBuilder = RestClient.builder().baseUrl("https://geo.test");
        RestClient.Builder forecastBuilder = RestClient.builder().baseUrl("https://forecast.test");
        MockRestServiceServer geocodingServer = MockRestServiceServer.bindTo(geocodingBuilder).build();
        MockRestServiceServer forecastServer = MockRestServiceServer.bindTo(forecastBuilder).build();
        OpenMeteoWeatherProvider provider = new OpenMeteoWeatherProvider(
                new OpenMeteoLocationResolver(geocodingBuilder.build(), new ObjectMapper(), CLOCK),
                forecastBuilder.build(), new ObjectMapper(), CLOCK);

        geocodingServer.expect(requestTo(
                        "https://geo.test/v1/search?name=กรุงเทพ&count=5"
                                + "&language=th&format=json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"results":[{"name":"Bangkok","country":"Thailand","latitude":13.75,
                        "longitude":100.50,"timezone":"Asia/Bangkok"}]}
                        """, MediaType.APPLICATION_JSON));
        forecastServer.expect(requestTo("https://forecast.test/v1/forecast?latitude=13.75&longitude=100.5"
                + "&current=temperature_2m,apparent_temperature,precipitation,wind_speed_10m,weather_code"
                + "&daily=weather_code,temperature_2m_min,temperature_2m_max,precipitation_probability_max,"
                + "precipitation_sum,sunrise,sunset&forecast_days=16&timezone=auto&temperature_unit=celsius"
                + "&wind_speed_unit=kmh&precipitation_unit=mm"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"current":{"temperature_2m":30.0,"weather_code":61},
                        "daily":{"time":["2026-08-18"],"weather_code":[61],
                        "temperature_2m_min":[27.0],"temperature_2m_max":[34.0],
                        "precipitation_probability_max":[70],"precipitation_sum":[5.0],
                        "sunrise":["06:00"],"sunset":["18:40"]}}
                        """, MediaType.APPLICATION_JSON));

        WeatherReport report = provider.forecast(new WeatherRequest("กรุงเทพฯ", "today", ""));

        assertEquals("Bangkok", report.location());
        assertEquals("2026-08-18", report.requestedDate());
        geocodingServer.verify();
        forecastServer.verify();
    }
}
