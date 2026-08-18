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

class OpenMeteoLocationResolverTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void resolvesThaiPlaceToCanonicalCoordinatesAndTimezone() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://geo.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenMeteoLocationResolver resolver = new OpenMeteoLocationResolver(
                builder.build(), new ObjectMapper(), CLOCK);

        server.expect(requestTo("https://geo.test/v1/search?name=กรุงเทพ&count=5&language=th&format=json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"results":[{"name":"Bangkok","country":"Thailand","country_code":"TH",
                        "admin1":"Bangkok","latitude":13.75,"longitude":100.50,
                        "timezone":"Asia/Bangkok"}]}
                        """, MediaType.APPLICATION_JSON));

        LocationResult result = resolver.resolve(new LocationRequest("กรุงเทพฯ", ""));

        assertEquals("Bangkok", result.name());
        assertEquals("Thailand", result.country());
        assertEquals("TH", result.countryCode());
        assertEquals("Bangkok", result.admin1());
        assertEquals(13.75, result.latitude());
        assertEquals(100.50, result.longitude());
        assertEquals("Asia/Bangkok", result.timezone());
        server.verify();
    }
}
