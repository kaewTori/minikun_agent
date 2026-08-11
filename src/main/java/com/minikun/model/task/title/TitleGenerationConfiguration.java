package com.minikun.model.task.title;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class TitleGenerationConfiguration {
    @Bean
    TitlePromptBuilder titlePromptBuilder() {
        return new TitlePromptBuilder();
    }

    @Bean
    TitleGenerationProvider titleGenerationProvider(
            @Value("${minikun.model.title.provider:ollama}") String provider,
            @Value("${minikun.model.title.ollama.base-url:http://127.0.0.1:11434}") String baseUrl,
            @Value("${minikun.model.title.ollama.model:qwen3.5:0.6b}") String model,
            @Value("${minikun.model.title.ollama.timeout:PT30S}") Duration timeout,
            TitlePromptBuilder promptBuilder) {
        if (!"ollama".equalsIgnoreCase(provider)) {
            return messages -> {
                throw new IllegalStateException("title provider is disabled");
            };
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
        return new OllamaTitleGenerationProvider(ollamaApi, model, timeout, promptBuilder);
    }
}
