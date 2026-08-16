package com.minikun.memory.event;

@FunctionalInterface
public interface ObservationPublisher { void publish(MinikunEvent event); }
