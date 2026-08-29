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
import com.minikun.model.task.OllamaTaskModelProvider;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration(proxyBeanMethods = false)
public class SearchConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchConfiguration.class);

    @Bean(name = "searxngSearchProvider")
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

    @Bean(name = "tavilySearchProvider")
    TavilySearchProvider tavilyProvider(
            ObjectMapper objectMapper,
            Clock memoryClock,
            @Value("${minikun.search.tavily.url:https://api.tavily.com}") String baseUrl,
            @Value("${minikun.search.tavily.timeout:10s}") Duration timeout,
            @Value("${minikun.search.tavily.api-key:}") String apiKey,
            @Value("${minikun.search.tavily.enabled:true}") boolean enabled,
            @Value("${minikun.search.tavily.search-depth:basic}") String searchDepth) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(requestFactory)
                .build();
        return new TavilySearchProvider(
                restClient, objectMapper, memoryClock, apiKey, enabled, searchDepth);
    }

    @Bean
    SearchProvider searchProvider(
            @Qualifier("tavilySearchProvider") TavilySearchProvider tavilyProvider,
            @Qualifier("searxngSearchProvider") SearchProvider searxngProvider,
            Clock memoryClock,
            MeterRegistry meterRegistry,
            @Value("${minikun.search.failover.enabled:true}") boolean failoverEnabled,
            @Value("${minikun.search.failover.cooldown:PT120S}") Duration cooldown,
            @Value("${minikun.search.failover.failure-threshold:3}") int failureThreshold) {
        if (!tavilyProvider.configured()) {
            LOGGER.info("process=search_configuration event=tavily_disabled reason=missing_or_disabled_api_key provider=searxng");
            return searxngProvider;
        }
        if (!failoverEnabled) {
            return tavilyProvider;
        }
        return new FailoverSearchProvider(
                tavilyProvider, searxngProvider, memoryClock, cooldown, failureThreshold, meterRegistry);
    }

    @Bean
    SearchManager searchManager(
            @Qualifier("searchProvider") SearchProvider provider,
            Clock memoryClock,
            @Value("${minikun.search.max-retries:1}") int maxRetries,
            SearchDeduplicator deduplicator,
            SearchBudgeter budgeter,
            SearchFormatter formatter,
            MeterRegistry meterRegistry,
            @Value("${minikun.search.parallel-queries.enabled:true}") boolean parallelQueries,
            @Value("${minikun.search.parallel-queries.max-concurrency:3}") int maxConcurrentQueries) {
        return new DefaultSearchManager(
                provider, memoryClock, maxRetries, deduplicator, budgeter, formatter, meterRegistry,
                parallelQueries, maxConcurrentQueries);
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
    SearchDecisionProvider searchDecisionProvider(
            ObjectMapper objectMapper,
            @Value("${minikun.search.decision.ollama.base-url:http://127.0.0.1:11434}") String baseUrl,
            @Value("${minikun.search.decision.ollama.model:hf.co/mradermacher/llama3.2-typhoon2-3b-GGUF:Q4_K_M}")
                    String model,
            @Value("${minikun.search.decision.connect-timeout:PT5S}") Duration connectTimeout,
            @Value("${minikun.search.decision.read-timeout:PT120S}") Duration readTimeout,
            @Value("${minikun.search.decision.timeout:PT15S}") Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        String normalizedBaseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        RestClient restClient = RestClient.builder()
                .baseUrl(normalizedBaseUrl + "/v1/chat/completions")
                .requestFactory(requestFactory)
                .build();
        TaskModelProvider taskModelProvider = new OllamaTaskModelProvider(
                restClient, objectMapper, model, readTimeout);
        return new TaskModelSearchDecisionProvider(taskModelProvider, objectMapper, timeout);
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
            @Value("${minikun.performance.fast-path.enabled:true}") boolean fastPathEnabled,
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
        decisionService = new FastPathSearchDecisionService(
                decisionService, new RuleBasedSearchDecisionService(meterRegistry), meterRegistry, fastPathEnabled);
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
