package com.minikun.search.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.search.SearchManager;
import com.minikun.search.SearchProvider;
import com.minikun.search.SearchDecisionService;
import com.minikun.search.SearchFormatter;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class SearchConfiguration {
    @Bean
    SearchProvider searxngProvider(
            ObjectMapper objectMapper,
            Clock memoryClock,
            @Value("${minikun.search.searxng.url:http://127.0.0.1:8080}") String baseUrl,
            @Value("${minikun.search.searxng.timeout:10s}") Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        return new SearXNGProvider(restClient, objectMapper, memoryClock);
    }

    @Bean
    SearchManager searchManager(
            SearchProvider provider,
            Clock memoryClock,
            @Value("${minikun.search.max-retries:1}") int maxRetries) {
        return new DefaultSearchManager(provider, memoryClock, maxRetries);
    }

    @Bean
    SearchDecisionService searchDecisionService() {
        return new RuleBasedSearchDecisionService();
    }

    @Bean
    SearchDeduplicator searchDeduplicator() {
        return new SearchDeduplicator();
    }

    @Bean
    SearchBudgeter searchBudgeter(
            @Value("${minikun.search.maximum-characters:4000}") int maximumCharacters) {
        return new SearchBudgeter(maximumCharacters);
    }

    @Bean
    SearchFormatter searchFormatter() {
        return new SearchFormatter();
    }
}