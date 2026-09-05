package com.minikun.browser;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
public class BrowserConfiguration {
    @Bean
    BrowserContentService browserContentService(
            @Value("${minikun.browser.enabled:true}") boolean enabled,
            @Value("${minikun.browser.crawl4ai.base-url:http://127.0.0.1:11235}") String baseUrl,
            @Value("${minikun.browser.crawl4ai.token:}") String token,
            @Value("${minikun.browser.timeout:20s}") Duration timeout,
            @Value("${minikun.browser.max-urls:5}") int maxUrls,
            @Value("${minikun.browser.block-private-addresses:true}") boolean blockPrivateAddresses,
            @Value("${minikun.browser.max-content-characters:12000}") int maxContentCharacters,
            @Value("${minikun.browser.max-concurrency:3}") int maxConcurrency,
            MeterRegistry meterRegistry) {
        if (maxUrls < 1) {
            throw new IllegalArgumentException("minikun.browser.max-urls must be at least 1");
        }
        if (maxContentCharacters < 1) {
            throw new IllegalArgumentException("minikun.browser.max-content-characters must be positive");
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("minikun.browser.max-concurrency must be positive");
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        RestClient restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        return new BrowserContentService(
                new Crawl4AiBrowserContentClient(restClient, token), enabled, maxUrls, meterRegistry,
                new BrowserUrlPolicy(blockPrivateAddresses), maxContentCharacters, maxConcurrency);
    }
}
