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

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "minikun.investment.enabled", havingValue = "true", matchIfMissing = true)
public class InvestmentExternalDataConfiguration {
    @Bean
    InvestmentExternalDataService investmentExternalDataService(
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${minikun.investment.external.timeout:15s}") Duration timeout,
            @Value("${minikun.investment.market.twelve-data.url:https://api.twelvedata.com}") String twelveDataUrl,
            @Value("${minikun.investment.market.twelve-data.api-key:}") String twelveDataApiKey,
            @Value("${minikun.investment.fx.frankfurter.url:https://api.frankfurter.dev}") String frankfurterUrl,
            @Value("${minikun.investment.sec.url:https://data.sec.gov}") String secUrl,
            @Value("${minikun.investment.sec.ticker-url:https://www.sec.gov}") String secTickerUrl,
            @Value("${minikun.investment.sec.user-agent:MinikunAgent/1.0 (contact: minikun@example.com)}") String secUserAgent,
            @Value("${minikun.investment.alpaca.paper.url:https://paper-api.alpaca.markets}") String alpacaPaperUrl,
            @Value("${minikun.investment.alpaca.key-id:}") String alpacaKeyId,
            @Value("${minikun.investment.alpaca.secret:}") String alpacaSecret) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("minikun.investment.external.timeout must be positive");
        }
        return new InvestmentExternalDataService(
                client(twelveDataUrl, timeout),
                client(frankfurterUrl, timeout),
                client(secUrl, timeout),
                client(secTickerUrl, timeout),
                client(alpacaPaperUrl, timeout),
                objectMapper, clock, twelveDataApiKey, secUserAgent, alpacaKeyId, alpacaSecret);
    }

    private RestClient client(String baseUrl, Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
