package com.minikun.memory.event;

public final class NoOpObservationPublisher implements ObservationPublisher {
    @Override public void publish(MinikunEvent event) { }
}
