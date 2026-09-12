package com.minikun.investment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class QuantDingerClientTest {
    @Test
    void submitsBacktestWithScopedAuthAndIdempotencyKey() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://qd.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://qd.test/api/agent/v1/backtest/run"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer qd-agent-token"))
                .andExpect(header("Idempotency-Key", "backtest-1"))
                .andRespond(withSuccess(
                        "{\"code\":0,\"message\":\"queued\",\"data\":{\"job_id\":\"job-1\",\"status\":\"queued\"}}",
                        MediaType.APPLICATION_JSON));

        QuantDingerClient client = new QuantDingerClient(
                builder.build(), new ObjectMapper(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), true,
                "https://qd.test", "qd-agent-token");

        Map<String, Object> result = client.submitBacktest(
                Map.of("code", "def initialize(context): pass", "startDate", "2025-01-01", "endDate", "2025-12-31"),
                "backtest-1");

        Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertEquals("job-1", data.get("job_id"));
        assertTrue(result.containsKey("observed_at"));
        server.verify();
    }
}
