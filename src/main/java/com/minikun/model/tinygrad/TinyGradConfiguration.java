package com.minikun.model.tinygrad;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class TinyGradConfiguration {
    @Bean
    TinyGradClient tinyGradClient(
            ObjectMapper objectMapper,
            @Value("${minikun.model.tinygrad.base-url:http://localhost:8001/v1}") String baseUrl,
            @Value("${minikun.model.tinygrad.model:}") String model,
            @Value("${minikun.model.tinygrad.timeout:PT120S}") Duration timeout,
            @Value("${minikun.model.tinygrad.force-greedy:true}") boolean forceGreedy) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        return new HttpTinyGradClient(httpClient, objectMapper, baseUrl, model, timeout, forceGreedy);
    }

    @Bean
    TinyGradHealthIndicator tinyGradHealthIndicator(
            ObjectMapper objectMapper,
            @Value("${minikun.model.tinygrad.base-url:http://localhost:8001/v1}") String baseUrl,
            @Value("${minikun.model.tinygrad.health.timeout:PT2S}") Duration timeout,
            @Value("${minikun.model.active:existing}") String activeProvider,
            @Value("${minikun.model.task.provider:ollama}") String taskProvider,
            @Value("${minikun.model.title.provider:ollama}") String titleProvider) {
        boolean required = Stream.of(activeProvider, taskProvider, titleProvider)
                .anyMatch(value -> "tinygrad".equalsIgnoreCase(value));
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        return new TinyGradHealthIndicator(httpClient, objectMapper, baseUrl, timeout, required);
    }
}
