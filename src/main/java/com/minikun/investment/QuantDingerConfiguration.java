package com.minikun.investment;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Creates the optional, read/backtest-only QuantDinger sidecar client. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "minikun.investment.enabled", havingValue = "true", matchIfMissing = true)
public class QuantDingerConfiguration {
    @Bean
    QuantDingerClient quantDingerClient(
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${minikun.investment.quantdinger.enabled:false}") boolean enabled,
            @Value("${minikun.investment.quantdinger.url:http://127.0.0.1:8888}") String baseUrl,
            @Value("${minikun.investment.quantdinger.agent-token:}") String agentToken,
            @Value("${minikun.investment.quantdinger.timeout:15s}") Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("minikun.investment.quantdinger.timeout must be positive");
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        RestClient client = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        return new QuantDingerClient(client, objectMapper, clock, enabled, baseUrl, agentToken);
    }
}
