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
import com.minikun.memory.ReflectionService;
import com.minikun.memory.ReflectionDecisionService;
import com.minikun.memory.reflection.ReflectionClient;
import com.minikun.memory.reflection.ReflectionParser;
import com.minikun.memory.reflection.ReflectionPromptBuilder;
import com.minikun.pcs.MinikunPersonaProvider;

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

    @Bean
    @ConditionalOnProperty(name = "minikun.memory.reflection.enabled", havingValue = "true")
    ReflectionPromptBuilder reflectionPromptBuilder(
            @Qualifier("memoryObjectMapper") ObjectMapper objectMapper,
            MinikunPersonaProvider personaProvider) {
        return new ReflectionPromptBuilder(objectMapper, personaProvider);
    }

    @Bean
    @ConditionalOnProperty(name = "minikun.memory.reflection.enabled", havingValue = "true")
    ReflectionParser reflectionParser(@Qualifier("memoryObjectMapper") ObjectMapper objectMapper) {
        return new ReflectionParser(objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "minikun.memory.reflection.enabled", havingValue = "true")
    ReflectionClient reflectionClient(
            @Value("${minikun.memory.reflection.url:http://127.0.0.1:11434/api/chat}") String url,
            @Value("${minikun.memory.reflection.model:main-model}") String model,
            @Value("${minikun.memory.reflection.timeout:150s}") Duration timeout) {
        var httpClient = java.net.http.HttpClient.newBuilder().connectTimeout(timeout).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return new ReflectionHttpClient(RestClient.builder().baseUrl(url)
                .requestFactory(requestFactory).build(), model);
    }

    @Bean
    @ConditionalOnProperty(name = "minikun.memory.reflection.enabled", havingValue = "true")
    ReflectionDecisionService reflectionDecisionService() {
        return new ReflectionDecisionService();
    }

    @Bean
    @ConditionalOnProperty(name = "minikun.memory.reflection.enabled", havingValue = "true")
    ReflectionService reflectionService(ReflectionPromptBuilder promptBuilder, ReflectionClient client,
            ReflectionParser parser, ReflectionDecisionService decisionService,
            MemoryRepository repository, Clock memoryClock) {
        return new ReflectionService(promptBuilder, client, parser, decisionService, repository, memoryClock);
    }
}