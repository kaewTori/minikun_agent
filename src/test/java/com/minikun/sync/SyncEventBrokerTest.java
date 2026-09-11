package com.minikun.sync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SyncEventBrokerTest {
    @Test
    void reportsWhetherAnOwnerHasAnActiveSseClient() {
        SyncEventBroker broker = new SyncEventBroker();

        assertFalse(broker.publishNotification("default", Map.of("title", "Test")));
        SseEmitter emitter = broker.connect("default");

        assertTrue(broker.publishNotification("default", Map.of("title", "Test")));

        emitter.complete();
    }
}
