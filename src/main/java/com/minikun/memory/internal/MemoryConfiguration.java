package com.minikun.memory.internal;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.MemoryAnalyzer;
import com.minikun.memory.MemoryExtractionClient;
import com.minikun.memory.MemoryPolicy;
import com.minikun.memory.MemoryRecallService;
import com.minikun.memory.MemoryRepository;
import com.minikun.memory.MemoryService;

@Configuration(proxyBeanMethods = false)
public class MemoryConfiguration {
    @Bean(name = "memoryObjectMapper")
    ObjectMapper memoryObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    MemoryPolicy memoryPolicy(
            @Value("${minikun.memory.minimum-confidence:0.7}") double minimumConfidence,
            @Value("${minikun.memory.maximum-content-length:500}") int maximumContentLength) {
        return new MemoryPolicy(minimumConfidence, maximumContentLength);
    }

    @Bean
    Clock memoryClock() {
        return Clock.systemUTC();
    }

    @Bean
    MemoryPromptBuilder memoryPromptBuilder(@Qualifier("memoryObjectMapper") ObjectMapper objectMapper) {
        return new MemoryPromptBuilder(objectMapper);
    }

    @Bean
    MemoryResponseParser memoryResponseParser(@Qualifier("memoryObjectMapper") ObjectMapper objectMapper) {
        return new MemoryResponseParser(objectMapper);
    }

    @Bean
    MemoryValidator memoryValidator() {
        return new MemoryValidator();
    }

    @Bean
    @ConditionalOnProperty(name = "minikun.memory.persistence.enabled", havingValue = "true", matchIfMissing = true)
    MemoryRepository memoryRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcMemoryRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnBean(MemoryRepository.class)
    MemoryService memoryService(MemoryRepository repository, Clock memoryClock) {
        return new MemoryService(repository, memoryClock);
    }

    @Bean
    @ConditionalOnBean(MemoryRepository.class)
    MemoryRecallService memoryRecallService(
            MemoryRepository repository,
            @Value("${minikun.memory.recall.maximum-count:10}") int maximumCount,
            @Value("${minikun.memory.recall.maximum-characters:4000}") int maximumCharacters) {
        MemorySelector selector = new MemorySelector(maximumCount);
        MemoryFormatter formatter = new MemoryFormatter(maximumCharacters);
        return new MemoryRecallService(repository,
            memories -> formatter.format(selector.select(memories)));
    }

    @Bean
    MemoryExtractionClient mainModelMemoryClient(
            MemoryPromptBuilder promptBuilder,
            MemoryResponseParser responseParser,
            MemoryValidator validator,
            MemoryPolicy policy,
            Clock memoryClock,
                @Value("${minikun.memory.model:${spring.ai.ollama.chat.options.model:main-model}}") String model,
                @Value("${spring.ai.ollama.base-url:http://127.0.0.1:11434}") String baseUrl,
            @Value("${minikun.memory.main-model.timeout:150s}") Duration timeout) {
            var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
            var requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(timeout);
            OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
            return new MainModelMemoryClient(ollamaApi, model, timeout, memoryClock,
                promptBuilder, responseParser, validator, policy);
    }

    @Bean
    MemoryAnalyzer memoryAnalyzer(MemoryExtractionClient extractionClient, MemoryPolicy policy) {
        return new MemoryAnalyzer(extractionClient, policy);
    }
}