package com.minikun.memory.event;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SafeObservationPublisher implements ObservationPublisher {
    private static final Logger log = LoggerFactory.getLogger(SafeObservationPublisher.class);
    private final ObservationPublisher delegate;
    public SafeObservationPublisher(ObservationPublisher delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }
    @Override public void publish(MinikunEvent event) {
        try { delegate.publish(event); }
        catch (RuntimeException exception) { log.warn("observation_publish_failed type={}",
                event == null ? null : event.observation().type(), exception); }
    }
}
