package com.minikun.browser;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import io.micrometer.core.instrument.MeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class BrowserConfiguration {
    @Bean
    BrowserSessionClient browserSessionClient(ObjectMapper mapper,
            @Value("${minikun.browser.session.runtime-root:${user.home}/Library/Application Support/Minikun/browser}") String root,
            @Value("${minikun.browser.session.timeout:35s}") Duration timeout) {
        Path runtime = Path.of(root).toAbsolutePath();
        return new BrowserSessionClient(mapper, runtime.resolve("venv/bin/python"), runtime.resolve("session.py"),
                runtime.resolve("profile"), timeout);
    }

    @Bean
    ManagedBrowserContentClient managedBrowserContentClient(
            @Value("${minikun.browser.crawl4ai.base-url:http://127.0.0.1:11235}") String baseUrl,
            @Value("${minikun.browser.crawl4ai.token:}") String token,
            @Value("${minikun.browser.timeout:25s}") Duration timeout,
            @Value("${minikun.browser.page-timeout:15s}") Duration pageTimeout,
            @Value("${minikun.browser.settle-delay:1500ms}") Duration settleDelay,
            @Value("${minikun.browser.retry-delay:1s}") Duration retryDelay,
            @Value("${minikun.browser.cache-ttl:5m}") Duration cacheTtl,
            @Value("${minikun.browser.cache-capacity:128}") int cacheCapacity,
            @Value("${minikun.browser.domain-interval:2s}") Duration domainInterval,
            @Value("${minikun.browser.max-concurrency:3}") int maxConcurrency,
            BrowserSessionClient session) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(pageTimeout.plusSeconds(5)) < 0) {
            throw new IllegalArgumentException("browser HTTP timeout must allow page timeout plus five seconds");
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        RestClient restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        BrowserContentClient crawler = new Crawl4AiBrowserContentClient(restClient, token, pageTimeout, settleDelay, retryDelay);
        return new ManagedBrowserContentClient(url -> session.handles(url) ? session.render(url) : crawler.render(url),
                cacheTtl, domainInterval, cacheCapacity, maxConcurrency);
    }

    @Bean
    BrowserContentService browserContentService(
            @Value("${minikun.browser.enabled:true}") boolean enabled,
            @Value("${minikun.browser.max-urls:5}") int maxUrls,
            @Value("${minikun.browser.block-private-addresses:true}") boolean blockPrivateAddresses,
            @Value("${minikun.browser.max-content-characters:12000}") int maxContentCharacters,
            @Value("${minikun.browser.max-concurrency:3}") int maxConcurrency,
            MeterRegistry meterRegistry, ManagedBrowserContentClient client) {
        if (maxUrls < 1) {
            throw new IllegalArgumentException("minikun.browser.max-urls must be at least 1");
        }
        if (maxContentCharacters < 1) {
            throw new IllegalArgumentException("minikun.browser.max-content-characters must be positive");
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("minikun.browser.max-concurrency must be positive");
        }
        return new BrowserContentService(
                client, enabled, maxUrls, meterRegistry,
                new BrowserUrlPolicy(blockPrivateAddresses), maxContentCharacters, maxConcurrency);
    }
}
