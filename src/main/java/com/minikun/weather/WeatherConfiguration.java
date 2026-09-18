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
            LocationResolver locationResolver,
            ObjectMapper objectMapper,
            Clock memoryClock,
            @Value("${minikun.weather.enabled:true}") boolean enabled,
            @Value("${minikun.weather.forecast-url:https://api.open-meteo.com}") String forecastUrl,
            @Value("${minikun.weather.timeout:10s}") Duration timeout) {
        WeatherProvider provider = new OpenMeteoWeatherProvider(
                locationResolver,
                restClient(forecastUrl, timeout),
                objectMapper, memoryClock);
        return enabled ? provider : request -> {
            throw new IllegalStateException("weather capability is disabled");
        };
    }

    @Bean
    LocationResolver locationResolver(
            ObjectMapper objectMapper,
            Clock memoryClock,
            @Value("${minikun.weather.enabled:true}") boolean enabled,
            @Value("${minikun.weather.geocoding-url:https://geocoding-api.open-meteo.com}") String geocodingUrl,
            @Value("${minikun.weather.timeout:10s}") Duration timeout) {
        LocationResolver resolver = new OpenMeteoLocationResolver(
                restClient(geocodingUrl, timeout),
                objectMapper, memoryClock);
        return enabled ? resolver : request -> {
            throw new IllegalStateException("weather capability is disabled");
        };
    }

    @Bean
    ReverseGeocodingService reverseGeocodingService(
            ObjectMapper objectMapper,
            Clock memoryClock,
            @Value("${minikun.location.reverse-geocoding.enabled:true}") boolean enabled,
            @Value("${minikun.location.reverse-geocoding.url:https://nominatim.openstreetmap.org}") String baseUrl,
            @Value("${minikun.location.reverse-geocoding.timeout:PT3S}") Duration timeout,
            @Value("${minikun.location.reverse-geocoding.max-age:PT10M}") Duration maximumAge,
            @Value("${minikun.location.reverse-geocoding.max-accuracy:250}") double maximumAccuracy,
            @Value("${minikun.location.reverse-geocoding.user-agent:MinikunAgent/1.0}") String userAgent) {
        return new ReverseGeocodingService(
                restClient(baseUrl, timeout), objectMapper, memoryClock,
                maximumAge, maximumAccuracy, userAgent, enabled);
    }

    private RestClient restClient(String baseUrl, Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
