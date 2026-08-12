package com.minikun.search.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.search.AliasDictionary;
import com.minikun.search.AcronymDictionary;
import com.minikun.search.SearchManager;
import com.minikun.search.SearchProvider;
import com.minikun.search.SearchQueryExpansionService;
import com.minikun.search.SearchQueryPlanningService;
import com.minikun.search.SearchQueryRewriteService;
import com.minikun.search.SearchDecisionService;
import com.minikun.search.SearchCache;
import com.minikun.search.SearchContextAwarenessService;
import com.minikun.search.SearchDecisionProvider;
import com.minikun.search.SearchService;
import com.minikun.model.task.TaskModelProvider;
import com.minikun.search.SynonymDictionary;
import com.minikun.search.dictionary.ImmutableAcronymDictionary;
import com.minikun.search.dictionary.ImmutableAliasDictionary;
import com.minikun.search.dictionary.ImmutableSynonymDictionary;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
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
            @Value("${minikun.search.max-retries:1}") int maxRetries,
            SearchDeduplicator deduplicator,
            SearchBudgeter budgeter,
            SearchFormatter formatter,
            MeterRegistry meterRegistry) {
        return new DefaultSearchManager(
                provider, memoryClock, maxRetries, deduplicator, budgeter, formatter, meterRegistry);
    }

    @Bean
    SearchCache searchCache(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${minikun.search.cache.ttl:PT5M}") Duration ttl,
            MeterRegistry meterRegistry) {
        return new ValkeySearchCache(redis, objectMapper, ttl, meterRegistry);
    }

    @Bean
    SearchService searchService(
            SearchManager manager,
            SearchCache cache,
            SearchQueryRewriteService queryRewriteService,
            SearchQueryExpansionService queryExpansionService,
            @Value("${minikun.search.enabled:true}") boolean searchEnabled,
            @Value("${minikun.search.cache.enabled:true}") boolean cacheEnabled,
            MeterRegistry meterRegistry,
            @Value("${minikun.search.cache.provider-version:v1}") String providerVersion) {
        return new DefaultSearchService(
            manager, cache, searchEnabled, cacheEnabled,
            queryRewriteService, queryExpansionService, meterRegistry, providerVersion);
    }

    @Bean
    SearchQueryRewriteService searchQueryRewriteService() {
        return new DefaultSearchQueryRewriteService();
    }

    @Bean
    SearchQueryPlanningService searchQueryPlanningService(
            MeterRegistry meterRegistry,
            @Value("${minikun.search.query-planning.max-alternates:2}") int maxAlternates) {
        return new DefaultSearchQueryPlanningService(meterRegistry, maxAlternates);
    }

    @Bean
    ImageIntentDetector imageIntentDetector() {
        return new ImageIntentDetector();
    }

    @Bean
    SearchContextAwarenessService searchContextAwarenessService() {
        return new DefaultSearchContextAwarenessService();
    }

    @Bean
    SynonymDictionary synonymDictionary() {
        return new ImmutableSynonymDictionary();
    }

    @Bean
    AcronymDictionary acronymDictionary() {
        return new ImmutableAcronymDictionary();
    }

    @Bean
    AliasDictionary aliasDictionary() {
        return new ImmutableAliasDictionary();
    }

    @Bean
    SearchQueryExpansionService searchQueryExpansionService(
            SynonymDictionary synonymDictionary,
            AcronymDictionary acronymDictionary,
            AliasDictionary aliasDictionary) {
        return new RuleBasedSearchQueryExpansionService(List.of(
                new IdentityExpansionRule(),
                new SynonymExpansionRule(synonymDictionary),
                new AcronymExpansionRule(acronymDictionary),
                new AliasExpansionRule(aliasDictionary)));
    }

        @Bean
        SearchDecisionProvider searchDecisionProvider(TaskModelProvider taskModelProvider,
            ObjectMapper objectMapper) {
        return new TaskModelSearchDecisionProvider(taskModelProvider, objectMapper);
        }

    @Bean
    SearchDecisionPromptBuilder searchDecisionPromptBuilder() {
        return new SearchDecisionPromptBuilder();
    }

    @Bean
    SearchDecisionService searchDecisionService(
            SearchDecisionProvider client,
            Clock memoryClock,
            SearchDecisionPromptBuilder promptBuilder,
            MeterRegistry meterRegistry,
            ImageIntentDetector imageIntentDetector,
            @Value("${minikun.search.decision.mode:rule}") String mode) {
        SearchDecisionService ruleService = new RuleBasedSearchDecisionService(meterRegistry);
        SearchDecisionService decisionService = switch (mode) {
            case "rule" -> ruleService;
            case "llm" -> new LlmSearchDecisionService(
                    client, new RuleBasedSearchDecisionService(null), memoryClock,
                    promptBuilder, meterRegistry);
            default -> throw new IllegalArgumentException(
                    "Unsupported minikun.search.decision.mode: " + mode);
        };
        return new ImageIntentSearchDecisionService(decisionService, imageIntentDetector);
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
