package com.minikun.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ReverseGeocodingServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void resolvesOnlyAnAreaAndRoundsCoordinatesBeforeExternalLookup() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://geo.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReverseGeocodingService service = new ReverseGeocodingService(
                builder.build(), new ObjectMapper(), CLOCK, Duration.ofMinutes(10), 250,
                "MinikunAgent/1.0", true);
        server.expect(requestTo("https://geo.test/reverse?lat=13.7326&lon=100.5291&format=jsonv2&zoom=14"
                        + "&addressdetails=1&accept-language=th"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"address":{"neighbourhood":"สามย่าน","city_district":"ปทุมวัน",
                        "city":"กรุงเทพมหานคร","state":"กรุงเทพมหานคร","country":"ประเทศไทย",
                        "country_code":"th"}}
                        """, MediaType.APPLICATION_JSON));

        LocationResult result = service.resolve(new DeviceLocation(
                13.732639, 100.529052, 5.0, CLOCK.millis() - 1_000)).orElseThrow();

        assertEquals("สามย่าน, ปทุมวัน", result.name());
        assertEquals("TH", result.countryCode());
        assertEquals("OpenStreetMap Nominatim", result.source());
        server.verify();
    }

    @Test
    void rejectsStaleOrLowAccuracyLocationWithoutCallingProvider() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://geo.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReverseGeocodingService service = new ReverseGeocodingService(
                builder.build(), new ObjectMapper(), CLOCK, Duration.ofMinutes(10), 250,
                "MinikunAgent/1.0", true);

        assertTrue(service.resolve(new DeviceLocation(
                13.7, 100.5, 5.0, CLOCK.millis() - Duration.ofMinutes(11).toMillis())).isEmpty());
        assertTrue(service.resolve(new DeviceLocation(
                13.7, 100.5, 251.0, CLOCK.millis() - 1_000)).isEmpty());
        server.verify();
    }
}
