package com.minikun.model.task;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class TaskModelConfiguration {
    @Bean
    TaskModelProvider taskModelProvider(
            ObjectMapper objectMapper,
            @Value("${minikun.model.task.provider:ollama}") String provider,
            @Value("${minikun.model.task.ollama.base-url:http://127.0.0.1:11434}") String baseUrl,
            @Value("${minikun.model.task.ollama.model:qwen3.5:0.6b}") String model,
            @Value("${minikun.model.task.ollama.timeout:PT120S}") Duration timeout) {
        if (!"ollama".equalsIgnoreCase(provider)) {
            throw new IllegalArgumentException("Unsupported task model provider: " + provider);
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl + "/v1/chat/completions")
                .requestFactory(requestFactory)
                .build();
        return new OllamaTaskModelProvider(restClient, objectMapper, model, timeout);
    }

    @Bean
    TaskModelRegistry taskModelRegistry(TaskModelProvider provider) {
        return new TaskModelRegistry(Map.of(TaskModelId.OLLAMA, provider));
    }
}
