package com.minikun.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.minikun.memory.event.MinikunEvent;
import com.minikun.memory.event.NoOpObservationPublisher;
import com.minikun.memory.event.Observation;
import com.minikun.memory.event.ObservationPublisher;
import com.minikun.memory.event.ObservationSource;
import com.minikun.memory.event.ObservationType;
import com.minikun.memory.event.SafeObservationPublisher;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryEventTest {
    @Test
    void observationIsImmutableAndPublisherFailureDoesNotEscape() {
        Observation observation = new Observation(ObservationType.TURN_COMPLETED, ObservationSource.CHAT,
                "default", "conversation-1", Instant.now(), Map.of("request_id", "r1"));
        MinikunEvent event = MinikunEvent.from(observation);
        ObservationPublisher failing = ignored -> { throw new IllegalStateException("test"); };

        assertEquals(ObservationType.TURN_COMPLETED, event.observation().type());
        assertDoesNotThrow(() -> new SafeObservationPublisher(failing).publish(event));
        assertDoesNotThrow(() -> new NoOpObservationPublisher().publish(event));
    }
}
