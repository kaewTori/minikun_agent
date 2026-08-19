package com.minikun.calendar;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "minikun.calendar.external.enabled", havingValue = "true")
public class ExternalCalendarConfiguration {
    @Bean
    IcsCalendarParser icsCalendarParser() {
        return new IcsCalendarParser();
    }

    @Bean
    ExternalCalendarService externalCalendarService(
            IcsCalendarParser parser,
            Clock memoryClock,
            @Value("${minikun.calendar.external.feed-url:}") String feedUrl,
            @Value("${minikun.calendar.external.zone:Asia/Bangkok}") String zone,
            @Value("${minikun.calendar.external.source:personal}") String source,
            @Value("${minikun.calendar.external.timeout:10s}") Duration timeout,
            @Value("${minikun.calendar.external.cache-ttl:5m}") Duration cacheTtl,
            @Value("${minikun.calendar.external.allow-http:false}") boolean allowHttp) {
        if (feedUrl == null || feedUrl.isBlank()) {
            throw new IllegalArgumentException("MINIKUN_CALENDAR_EXTERNAL_FEED_URL is required when external calendar is enabled");
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        IcsCalendarClient client = new IcsCalendarClient(
                RestClient.builder().requestFactory(requestFactory).build(),
                URI.create(feedUrl.trim()), parser, ZoneId.of(zone.trim()), source, allowHttp);
        return new ExternalCalendarService(client, memoryClock, cacheTtl);
    }
}
