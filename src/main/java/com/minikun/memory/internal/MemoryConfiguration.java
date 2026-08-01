package com.minikun.memory.internal;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.MemoryAnalyzer;
import com.minikun.memory.MemoryExtractionClient;
import com.minikun.memory.MemoryPolicy;
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
    @ConditionalOnBean(JdbcTemplate.class)
    MemoryRepository memoryRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcMemoryRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnBean(MemoryRepository.class)
    MemoryService memoryService(MemoryRepository repository, Clock memoryClock) {
        return new MemoryService(repository, memoryClock);
    }

    @Bean
    MemoryExtractionClient llamaCppMemoryClient(
            MemoryPromptBuilder promptBuilder,
            MemoryResponseParser responseParser,
            MemoryValidator validator,
            MemoryPolicy policy,
            Clock memoryClock,
            @Value("${minikun.memory.llama-cpp.url:http://z-flip:8080/v1/chat/completions}") String url,
            @Value("${minikun.memory.llama-cpp.model:${OLLAMA_MODEL:hf.co/llmfan46/gemma-4-E4B-it-ultra-uncensored-heretic-GGUF:Q6_K}}") String model,
            @Value("${minikun.memory.llama-cpp.timeout:30s}") Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        RestClient restClient = RestClient.builder()
                .baseUrl(url)
                .requestFactory(requestFactory)
                .build();
        return new LlamaCppClient(restClient, model, timeout, memoryClock,
                promptBuilder, responseParser, validator, policy);
    }

    @Bean
    MemoryAnalyzer memoryAnalyzer(MemoryExtractionClient extractionClient, MemoryPolicy policy) {
        return new MemoryAnalyzer(extractionClient, policy);
    }
}