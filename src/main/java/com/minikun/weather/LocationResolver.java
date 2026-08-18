package com.minikun.weather;

public interface LocationResolver {
    LocationResult resolve(LocationRequest request);
}
