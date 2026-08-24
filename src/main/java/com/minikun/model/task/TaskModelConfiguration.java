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
            @Value("${minikun.model.task.ollama.base-url:http://127.0.0.1:11434}") String ollamaBaseUrl,
            @Value("${minikun.model.task.ollama.model:qwen3.5:0.6b}") String ollamaModel,
            @Value("${minikun.model.task.ollama.timeout:PT120S}") Duration ollamaTimeout,
            @Value("${minikun.model.tinygrad.base-url:http://127.0.0.1:8001/v1}") String tinyGradBaseUrl,
            @Value("${minikun.model.tinygrad.model:}") String tinyGradModel,
            @Value("${minikun.model.tinygrad.timeout:PT300S}") Duration tinyGradTimeout,
            @Value("${minikun.model.task.failover.enabled:true}") boolean failoverEnabled,
            @Value("${minikun.model.task.failover.cooldown:PT30S}") Duration failoverCooldown) {
        boolean tinyGrad = "tinygrad".equalsIgnoreCase(provider);
        if (!tinyGrad && !"ollama".equalsIgnoreCase(provider)) {
            throw new IllegalArgumentException("Unsupported task model provider: " + provider);
        }
        if (!tinyGrad) {
            return createProvider(ollamaBaseUrl, ollamaModel, ollamaTimeout, false, objectMapper);
        }
        TaskModelProvider tinyGradProvider = createProvider(
                tinyGradBaseUrl, tinyGradModel, tinyGradTimeout, true, objectMapper);
        if (!failoverEnabled) {
            return tinyGradProvider;
        }
        TaskModelProvider ollama = createProvider(
                ollamaBaseUrl, ollamaModel, ollamaTimeout, false, objectMapper);
        return new FailoverTaskModelProvider(tinyGradProvider, ollama, failoverCooldown);
    }

    private TaskModelProvider createProvider(
            String baseUrl,
            String model,
            Duration timeout,
            boolean tinyGrad,
            ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        RestClient restClient = RestClient.builder()
                .baseUrl(chatCompletionsUrl(baseUrl, tinyGrad))
                .requestFactory(requestFactory)
                .build();
        return new OllamaTaskModelProvider(restClient, objectMapper, model, timeout,
                tinyGrad ? "background" : null);
    }

    @Bean
    TaskModelRegistry taskModelRegistry(
            TaskModelProvider provider,
            @Value("${minikun.model.task.provider:ollama}") String configuredProvider) {
        TaskModelId id = "tinygrad".equalsIgnoreCase(configuredProvider)
                ? TaskModelId.TINYGRAD : TaskModelId.OLLAMA;
        return new TaskModelRegistry(Map.of(id, provider));
    }

    private String chatCompletionsUrl(String baseUrl, boolean tinyGrad) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized + (tinyGrad ? "/chat/completions" : "/v1/chat/completions");
    }
}
