package com.minikun.weather;

public interface WeatherProvider {
    WeatherReport forecast(WeatherRequest request);
}
