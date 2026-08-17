package com.minikun.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class WeatherConfiguration {
    @Bean
    WeatherProvider weatherProvider(
            ObjectMapper objectMapper,
            Clock memoryClock,
            @Value("${minikun.weather.enabled:true}") boolean enabled,
            @Value("${minikun.weather.geocoding-url:https://geocoding-api.open-meteo.com}") String geocodingUrl,
            @Value("${minikun.weather.forecast-url:https://api.open-meteo.com}") String forecastUrl,
            @Value("${minikun.weather.timeout:10s}") Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        WeatherProvider provider = new OpenMeteoWeatherProvider(
                RestClient.builder().baseUrl(geocodingUrl).requestFactory(requestFactory).build(),
                RestClient.builder().baseUrl(forecastUrl).requestFactory(requestFactory).build(),
                objectMapper, memoryClock);
        return enabled ? provider : request -> {
            throw new IllegalStateException("weather capability is disabled");
        };
    }
}
