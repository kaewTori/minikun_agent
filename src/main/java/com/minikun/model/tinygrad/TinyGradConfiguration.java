package com.minikun.model.tinygrad;

import java.net.http.HttpClient;
import java.time.Duration;

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
            @Value("${spring.ai.ollama.chat.options.num-ctx:16384}") int contextSize) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        return new HttpTinyGradClient(httpClient, objectMapper, baseUrl, model, timeout, contextSize);
    }
}
